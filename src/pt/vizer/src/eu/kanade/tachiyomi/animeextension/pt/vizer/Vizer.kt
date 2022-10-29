package eu.kanade.tachiyomi.animeextension.pt.vizer

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.EpisodeListDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.PlayersDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.SearchItemDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.SearchResultDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.VideoLanguagesDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.extractors.MixDropExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Vizer : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Vizer.tv"

    override val baseUrl = "https://vizer.tv"
    private val API_URL = "$baseUrl/includes/ajax"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val initialUrl = "$API_URL/ajaxPagination.php?categoryFilterOrderBy=vzViews&page=$page&categoryFilterOrderWay=desc&categoryFilterYearMin=1950&categoryFilterYearMax=2022"
        val pageType = preferences.getString(PREF_POPULAR_PAGE_KEY, "movie")!!
        val finalUrl = if ("movie" in pageType) {
            initialUrl + "&saga=0&categoriesListMovies=all"
        } else {
            (initialUrl + "&categoriesListSeries=all").let {
                if ("anime" in pageType) it + "&anime=1"
                else it + "&anime=0"
            }
        }
        return GET(finalUrl)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<SearchResultDto>()
        val animes = result.list.map(::animeFromObject).toList()
        val hasNext = result.quantity == 35
        return AnimesPage(animes, hasNext)
    }

    private fun animeFromObject(item: SearchItemDto): SAnime =
        SAnime.create().apply {
            var slug = if (item.status.isBlank()) "filme" else "serie"
            url = "/$slug/online/${item.url}"
            slug = if (slug == "filme") "movies" else "series"
            title = item.title
            status = when (item.status) {
                "Retornando" -> SAnime.ONGOING
                else -> SAnime.COMPLETED
            }
            thumbnail_url = "$baseUrl/content/$slug/posterPt/342/${item.id}.webp"
        }

    // ============================== Episodes ==============================

    private fun getSeasonEps(seasonElement: Element): List<SEpisode> {
        val id = seasonElement.attr("data-season-id")
        val sname = seasonElement.text()
        val response = client.newCall(apiRequest("getEpisodes=$id")).execute()
        val episodes = response.parseAs<EpisodeListDto>().episodes.mapNotNull {
            if (it.released)
                SEpisode.create().apply {
                    name = "Temp $sname: Ep ${it.name} - ${it.title}"
                    episode_number = it.name.toFloatOrNull() ?: 0F
                    url = it.id
                }
            else null
        }
        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasons = doc.select("div#seasonsList div.item[data-season-id]")
        return if (seasons.size > 0) {
            seasons.flatMap(::getSeasonEps).reversed()
        } else {
            listOf(
                SEpisode.create().apply {
                    name = "Filme"
                    episode_number = 1F
                    url = response.request.url.toString()
                }
            )
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val url = episode.url
        return if (url.startsWith("https")) {
            // Its an real url, maybe from a movie
            GET(url, headers)
        } else {
            // Fake url, its an ID that will be used to get episode languages
            // (sub/dub) and then return the video link
            apiRequest("getEpisodeLanguages=$url")
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        val videoObjectList = if (body.startsWith("{")) {
            json.decodeFromString<VideoLanguagesDto>(body).videos
        } else {
            val videoJson = body.substringAfterLast("videoPlayerBox(").substringBefore(");")
            json.decodeFromString<VideoLanguagesDto>(videoJson).videos
        }

        return videoObjectList.flatMap(::getVideosFromObject)
    }

    private fun getVideosFromObject(videoObj: VideoDto): List<Video> {
        val players = client.newCall(apiRequest("getVideoPlayers=${videoObj.id}"))
            .execute()
            .parseAs<PlayersDto>()
        val langPrefix = if (videoObj.lang == "1") "LEG" else "DUB"
        val videoList = players.iterator().mapNotNull loop@{ (name, status) ->
            if (status == "0") return@loop null
            val url = getPlayerUrl(videoObj.id, name)
            when {
                name == "mixdrop" ->
                    MixDropExtractor(client)
                        .videoFromUrl(url, langPrefix)?.let(::listOf)
                name == "streamtape" ->
                    StreamTapeExtractor(client)
                        .videoFromUrl(url, "StreamTape($langPrefix)")?.let(::listOf)
                name == "fembed" ->
                    FembedExtractor(client)
                        .videosFromUrl(url, langPrefix)
                else -> null
            }
        }.flatten()
        return videoList
    }

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = VizerFilters.filterList

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH).replace("/", "/online/")
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByPathParse(response, path)
                }
        } else {
            val params = VizerFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    private fun searchAnimeByPathParse(response: Response, path: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/" + path
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: VizerFilters.FilterSearchParams): Request {
        val urlBuilder = "$API_URL/ajaxPagination.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("search", query)
            .addQueryParameter("saga", "0")
            .addQueryParameter("categoryFilterYearMin", filters.minYear)
            .addQueryParameter("categoryFilterYearMax", filters.maxYear)
            .addQueryParameter("categoryFilterOrderBy", filters.orderBy)
            .addQueryParameter("categoryFilterOrderWay", filters.orderWay)

        if (filters.type == "Movies")
            urlBuilder.addQueryParameter("categoriesListMovies", filters.genre)
        else
            urlBuilder.addQueryParameter("categoriesListSeries", filters.genre)
        if (filters.type == "anime")
            urlBuilder.addQueryParameter("anime", "1")
        return GET(urlBuilder.build().toString(), headers)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("section.ai > h2").text()
            thumbnail_url = doc.selectFirst("meta[property=og:image]").attr("content")
            var desc = doc.selectFirst("span.desc").text() + "\n"
            doc.selectFirst("div.year")?.let { desc += "\nAno: ${it.text()}" }
            doc.selectFirst("div.tm")?.let { desc += "\nDuração: ${it.text()}" }
            doc.selectFirst("a.rating")?.let { desc += "\nNota: ${it.text()}" }
            description = desc
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = apiRequest("getHomeSliderSeries=1")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsedData = response.parseAs<SearchResultDto>()
        val animes = parsedData.list.map(::animeFromObject).toList()
        return AnimesPage(animes, false)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val popularPage = ListPreference(screen.context).apply {
            key = PREF_POPULAR_PAGE_KEY
            title = PREF_POPULAR_PAGE_TITLE
            entries = PREF_POPULAR_PAGE_ENTRIES
            entryValues = PREF_POPULAR_PAGE_VALUES
            setDefaultValue("anime")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val preferredPlayer = ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = PREF_PLAYER_TITLE
            entries = PREF_PLAYER_ARRAY
            entryValues = PREF_PLAYER_ARRAY
            setDefaultValue("MixDrop")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val preferredLanguage = ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue("LEG")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(popularPage)
        screen.addPreference(preferredPlayer)
        screen.addPreference(preferredLanguage)
    }

    // ============================= Utilities ==============================

    private fun getPlayerUrl(id: String, name: String): String {
        val req = GET("$baseUrl/embed/getPlay.php?id=$id&sv=$name")
        val body = client.newCall(req).execute().body?.string().orEmpty()
        return body.substringAfter("location.href=\"").substringBefore("\";")
    }

    private fun apiRequest(body: String): Request {
        val reqBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val newHeaders = headersBuilder().add("x-requested-with", "XMLHttpRequest")
            .build()
        return POST("$API_URL/publicFunctions.php", newHeaders, body = reqBody)
    }

    private fun List<Video>.sortIfContains(item: String): List<Video> {
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (item in video.quality) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun List<Video>.sort(): List<Video> {
        val player = preferences.getString(PREF_PLAYER_KEY, "MixDrop")!!
        val language = preferences.getString(PREF_LANGUAGE_KEY, "LEG")!!
        val newList = this.sortIfContains(language).sortIfContains(player)
        return newList
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    companion object {
        private const val PREF_POPULAR_PAGE_KEY = "pref_popular_page"
        private const val PREF_POPULAR_PAGE_TITLE = "Página de Populares"
        private val PREF_POPULAR_PAGE_ENTRIES = arrayOf(
            "Animes", "Filmes", "Séries"
        )
        private val PREF_POPULAR_PAGE_VALUES = arrayOf(
            "anime", "movie", "serie"
        )

        private const val PREF_PLAYER_KEY = "pref_player"
        private const val PREF_PLAYER_TITLE = "Player/Server favorito"
        private val PREF_PLAYER_ARRAY = arrayOf(
            "MixDrop", "StreamTape", "Fembed"
        )

        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_TITLE = "Língua/tipo preferido"
        private val PREF_LANGUAGE_ENTRIES = arrayOf("Legendado", "Dublado")
        private val PREF_LANGUAGE_VALUES = arrayOf("LEG", "DUB")

        const val PREFIX_SEARCH = "path:"
    }
}

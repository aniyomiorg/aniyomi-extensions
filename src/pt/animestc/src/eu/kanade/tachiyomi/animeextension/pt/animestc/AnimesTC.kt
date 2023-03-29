package eu.kanade.tachiyomi.animeextension.pt.animestc

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animestc.ATCFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.ResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.AnonFilesExtractor
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.LinkBypasser
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.SendcmExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit.DAYS

class AnimesTC : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimesTC"

    override val baseUrl = "https://api2.animestc.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "https://www.animestc.net/")

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // ============================== Popular ===============================
    // This source doesnt have a popular animes page,
    // so we use latest animes page instead.
    override fun fetchPopularAnime(page: Int) = fetchLatestUpdates(page)
    override fun popularAnimeParse(response: Response): AnimesPage = TODO()
    override fun popularAnimeRequest(page: Int): Request = TODO()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val id = response.getAnimeDto().id
        return getEpisodeList(id)
    }

    private fun episodeListRequest(animeId: Int, page: Int) =
        GET("$baseUrl/episodes?order=id&direction=desc&page=$page&seriesId=$animeId&specialOrder=true")

    private fun getEpisodeList(animeId: Int, page: Int = 1): List<SEpisode> {
        val response = client.newCall(episodeListRequest(animeId, page)).execute()
        val parsed = response.parseAs<ResponseDto<EpisodeDto>>()
        val episodes = parsed.items.map {
            SEpisode.create().apply {
                name = it.title
                setUrlWithoutDomain("/episodes?slug=${it.slug}")
                episode_number = it.number.toFloat()
                date_upload = it.created_at.toDate()
            }
        }

        if (parsed.page < parsed.lastPage) {
            return episodes + getEpisodeList(animeId, page + 1)
        } else {
            return episodes
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val videoDto = response.parseAs<ResponseDto<VideoDto>>().items.first()
        val links = videoDto.links
        val allLinks = listOf(links.low, links.medium, links.high).flatten()
        val supportedPlayers = listOf("anonfiles", "send")
        val online = links.online?.filterNot { "mega" in it }?.map {
            Video(it, "Player ATC", it, headers)
        } ?: emptyList<Video>()
        return online + allLinks.filter { it.name in supportedPlayers }.parallelMap {
            val playerUrl = LinkBypasser(client, json).bypass(it, videoDto.id)
            if (playerUrl == null) return@parallelMap null
            val quality = when (it.quality) {
                "low" -> "SD"
                "medium" -> "HD"
                "high" -> "FULLHD"
                else -> "SD"
            }
            when (it.name) {
                "anonfiles" ->
                    AnonFilesExtractor(client)
                        .videoFromUrl(playerUrl, quality)
                "send" ->
                    SendcmExtractor(client)
                        .videoFromUrl(playerUrl, quality)
                else -> null
            }
        }.filterNotNull()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.getAnimeDto()
        return SAnime.create().apply {
            setUrlWithoutDomain("/series/${anime.id}")
            title = anime.title
            status = anime.status
            genre = anime.genres
            description = anime.synopsis
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        TODO("Not yet implemented")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/series?slug=$slug"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response)
                }
        } else {
            val params = ATCFilters.getSearchParameters(filters)
            return Observable.just(searchAnime(page, query, params))
        }
    }

    private val allAnimesList by lazy {
        val cache = CacheControl.Builder().maxAge(1, DAYS).build()
        listOf("movie", "ova", "series").map { type ->
            val url = "$baseUrl/series?order=title&direction=asc&page=1&full=true&type=$type"
            val response = client.newCall(GET(url, cache = cache)).execute()
            response.parseAs<ResponseDto<AnimeDto>>().items
        }.flatten()
    }

    override fun getFilterList(): AnimeFilterList = ATCFilters.filterList

    private fun searchAnime(page: Int, query: String, filterParams: ATCFilters.FilterSearchParams): AnimesPage {
        filterParams.animeName = query
        val filtered = allAnimesList.applyFilterParams(filterParams)
        val results = filtered.chunked(30)
        val hasNextPage = results.size > page
        val currentPage = if (results.size == 0) {
            emptyList<SAnime>()
        } else {
            results.get(page - 1).map(::searchAnimeFromObject)
        }
        return AnimesPage(currentPage, hasNextPage)
    }

    private fun searchAnimeFromObject(anime: AnimeDto) = SAnime.create().apply {
        thumbnail_url = anime.cover.url
        title = anime.title
        setUrlWithoutDomain("/series/${anime.id}")
    }

    private fun searchAnimeBySlugParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsed = response.parseAs<ResponseDto<EpisodeDto>>()
        val hasNextPage = parsed.page < parsed.lastPage
        val animes = parsed.items.map {
            SAnime.create().apply {
                title = it.title
                setUrlWithoutDomain("/series/${it.animeId}")
                thumbnail_url = it.cover!!.url
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/episodes?order=created_at&direction=desc&page=$page&ignoreIndex=false")
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val playerPref = ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = PREF_PLAYER_TITLE
            entries = PREF_PLAYER_VALUES
            entryValues = PREF_PLAYER_VALUES
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(playerPref)
    }

    // ============================= Utilities ==============================
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun Response.getAnimeDto(): AnimeDto {
        val responseBody = body.string()
        return try {
            parseAs<AnimeDto>(responseBody)
        } catch (e: Exception) {
            // URL intent handler moment
            parseAs<ResponseDto<AnimeDto>>(responseBody).items.first()
        }
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        }.getOrNull() ?: 0L
    }

    private inline fun <reified T> Response.parseAs(preloaded: String? = null): T {
        val responseBody = preloaded ?: body.string()
        return json.decodeFromString(responseBody)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(player) },
                { it.quality.contains("- $quality") },
            ),
        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "slug:"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_VALUES = arrayOf("SD", "HD", "FULLHD")

        private const val PREF_PLAYER_KEY = "pref_player"
        private const val PREF_PLAYER_TITLE = "Player preferido"
        private const val PREF_PLAYER_DEFAULT = "AnonFiles"
        private val PREF_PLAYER_VALUES = arrayOf("AnonFiles", "Sendcm", "Player ATC")
    }
}

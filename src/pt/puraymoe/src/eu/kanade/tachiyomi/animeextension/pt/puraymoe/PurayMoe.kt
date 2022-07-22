package eu.kanade.tachiyomi.animeextension.pt.puraymoe

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.EpisodeDataDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.MinimalEpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.SearchDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.SeasonInfoDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.SeasonListDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class PurayMoe : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Puray.moe"

    override val baseUrl = "https://puray.moe"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$API_URL/animes/genero/25/")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<List<AnimeDto>>()
        val animes = animeList.map(::animeDetailsFromObject).toList()
        return AnimesPage(animes, false)
    }

    // ============================== Episodes ==============================

    private fun getSeasonList(anime: SAnime): SeasonListDto {
        val id = anime.url.getId()
        val request = GET("$API_URL/temporadas/?anime__id_animes=$id")
        val response = client.newCall(request).execute()
        return response.parseAs<SeasonListDto>()
    }

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val seasonsList: SeasonListDto = getSeasonList(anime)

        val showOnly = preferences.getString(CONF_SHOW_ONLY, null) ?: ""
        val dub_item = ANIME_TYPES_VALUES.elementAt(1)
        val sub_item = ANIME_TYPES_VALUES.last()
        var filteredSeasons = seasonsList.seasons.filter {
            val lowerName = it.name.lowercase()
            when (showOnly) {
                dub_item -> lowerName.contains(dub_item)
                sub_item -> !lowerName.contains(dub_item)
                else -> true
            }
        }
        if (filteredSeasons.size < 1) filteredSeasons = seasonsList.seasons

        val episodeList = mutableListOf<SEpisode>()
        filteredSeasons.reversed().forEach {
            val request: Request = episodeListRequest(it.id)
            val response: Response = client.newCall(request).execute()
            val season_episodes = episodeListParse(response, it)
            episodeList.addAll(season_episodes.reversed())
        }
        return Observable.just(episodeList)
    }

    override fun episodeListRequest(anime: SAnime): Request =
        throw Exception("not used")

    private fun episodeListRequest(season_id: Int): Request =
        GET("$API_URL/episodios/?temporada__id_temporadas=$season_id")

    override fun episodeListParse(response: Response) = throw Exception("not used")

    private fun episodeListParse(response: Response, season: SeasonInfoDto): List<SEpisode> {
        val episodesData = response.parseAs<EpisodeDataDto>()
        val seasonNumber = if (season.number.equals("0")) "1" else season.number
        val format = if ("dub" in season.name.lowercase()) "DUBLADO" else "LEGENDADO"
        return episodesData.episodes.map {
            val episode = SEpisode.create()
            episode.name = "Temp $seasonNumber ($format) EP ${it.ep_number}: ${it.name}"
            episode.episode_number = try {
                it.ep_number.toFloat()
            } catch (e: NumberFormatException) { 0F }
            episode.url = it.id.toString()
            episode.date_upload = it.release_date.toDate()
            episode
        }.toList()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$API_URL/episodios/${episode.url}/m3u8/mp4/")

    override fun videoListParse(response: Response): List<Video> {
        val episodeObject = response.parseAs<MinimalEpisodeDto>()
        return episodeObject.streams!!.map {
            val quality = "${it.quality.last()}p"
            Video(it.url, quality, it.url, null)
        }.toList()
    }

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<SearchDto>()
        val animes = parsed.results.map {
            SAnime.create().apply {
                title = it.name
                thumbnail_url = it.thumbnail
                url = "/anime/${it.id}"
            }
        }.toList()
        return AnimesPage(animes, false)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$API_URL/animes/$id"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, id)
                }
        } else {
            client.newCall(searchAnimeRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/anime/$id"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$API_URL/animes/fulltext/?q=$query")

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url)

    private fun animeDetailsApiRequest(anime: SAnime): Request {
        val id = anime.url.getId()
        return GET("$API_URL/animes/$id")
    }

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsApiRequest(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val animeData = response.parseAs<AnimeDto>()
        return animeDetailsFromObject(animeData)
    }

    private fun animeDetailsFromObject(anime: AnimeDto) = SAnime.create().apply {
        url = "/anime/${anime.id}"
        thumbnail_url = anime.thumbnail
        title = anime.name
        genre = anime.genres
            ?.joinToString(", ") { it.name }
        description = anime.description
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$API_URL/episodios/last/")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsedData = response.parseAs<List<MinimalEpisodeDto>>()
        val animes = parsedData.map(::getAnimeFromEpisode).toList()
        return AnimesPage(animes, false)
    }

    private fun getAnimeFromEpisode(episode: MinimalEpisodeDto) = SAnime.create().apply {
        val anime = episode.season!!.anime
        title = anime.name
        thumbnail_url = anime.thumbnail
        url = "/anime/${anime.id}"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = CONF_PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(QUALITY_LIST.last())
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val showOnlyPref = ListPreference(screen.context).apply {
            key = CONF_SHOW_ONLY
            title = "Mostrar apenas episódios:"
            entries = ANIME_TYPES
            entryValues = ANIME_TYPES_VALUES
            setDefaultValue(ANIME_TYPES_VALUES.first())
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(showOnlyPref)
    }

    // ============================= Utilities ==============================

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    private fun String.toDate(): Long {
        return try {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    private fun String.getId(): String = this.substringAfterLast("/")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(CONF_PREFERRED_QUALITY, null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.equals(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    companion object {
        private const val API_URL = "https://api.puray.moe"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val CONF_PREFERRED_QUALITY = "preferred_quality"
        private const val CONF_SHOW_ONLY = "show_only"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        private val QUALITY_LIST = arrayOf(
            "240p", "360p",
            "480p", "720p", "1080p"
        )

        private val ANIME_TYPES = arrayOf("Todos", "Dublados", "Legendados")
        private val ANIME_TYPES_VALUES = arrayOf("", "dub", "sub")

        const val PREFIX_SEARCH = "id:"
    }
}

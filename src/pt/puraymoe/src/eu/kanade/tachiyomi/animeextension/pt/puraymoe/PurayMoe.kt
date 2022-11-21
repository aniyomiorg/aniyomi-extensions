package eu.kanade.tachiyomi.animeextension.pt.puraymoe

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.EpisodeDataDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.EpisodeVideoDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.MinimalEpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.SearchDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.SeasonInfoDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.SeasonListDto
import eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto.VideoDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class PurayMoe : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Puray.moe"

    override val baseUrl = "https://puray.moe"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

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

        val showOnly = preferences.getString(PREF_SHOW_ONLY_KEY, null) ?: ""
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
            episode.episode_number = runCatching {
                it.ep_number.toFloat()
            }.getOrNull() ?: 0F
            episode.url = it.id.toString()
            episode.date_upload = it.release_date.toDate()
            episode
        }.toList()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode) =
        GET("$baseUrl/watch/${episode.url}")

    override fun videoListParse(response: Response): List<Video> {
        val nextData = response.asJsoup()
            .selectFirst("script#__NEXT_DATA__")
            .data()
            .let { json.decodeFromString<JsonObject>(it) }

        val episode = nextData.get("props")
            ?.jsonObject
            ?.get("pageProps")?.jsonObject
            ?.get("episode")?.jsonObject
            ?.let {
                json.decodeFromJsonElement(EpisodeVideoDto.serializer(), it)
            }

        return episode?.streams?.toVideoList(episode.subUrl)
            ?: getVideosFromAPI(response.request.url.toString().getId())
    }

    private fun getVideosFromAPI(episodeId: String): List<Video> {
        val url = "$API_URL/episodios/$episodeId/m3u8"
        val usePlaylist = preferences.getBoolean(PREF_USE_PLAYLIST_KEY, true)
        val request = if (usePlaylist) GET(url) else GET("$url/mp4/")
        val episodeObject = runCatching {
            val response = client.newCall(request).execute()
            response.parseAs<MinimalEpisodeDto>()
        }.getOrNull() ?: return run {
            // Lets try again, sometimes the source simply doesnt send all data
            // needed in the json (script#__NEXT_DATA__)
            // and makes videoListParse use this function by mistake.
            // As this function is (correctly) used in almost all cases,
            // we only retry here, because there is a higher chance that
            // the source will not return the data but the API call will work
            val newRequest = GET("$baseUrl/watch/$episodeId")
            videoListParse(client.newCall(newRequest).execute())
        }
        return if (usePlaylist) {
            client.newCall(GET(episodeObject.url))
                .execute()
                .toVideoList()
        } else {
            episodeObject.streams?.toVideoList() ?: emptyList<Video>()
        }
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
        val animes = parsedData.map(::getAnimeFromEpisode)
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
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue("720p")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val showOnlyPref = ListPreference(screen.context).apply {
            key = PREF_SHOW_ONLY_KEY
            title = PREF_SHOW_ONLY_TITLE
            entries = ANIME_TYPES_ENTRIES
            entryValues = ANIME_TYPES_VALUES
            setDefaultValue("")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val usePlaylistPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_PLAYLIST_KEY
            title = PREF_USE_PLAYLIST_TITLE
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean(key, checkValue).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(showOnlyPref)
        screen.addPreference(usePlaylistPref)
    }

    // ============================= Utilities ==============================

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    private fun List<VideoDto>.toVideoList(subUrl: String? = null): List<Video> {
        val subs = subUrl?.let { listOf(Track(it, "pt-br")) } ?: emptyList()
        return map {
            val quality = "${it.quality.last()}p"
            Video(it.url, quality, it.url, subtitleTracks = subs)
        }
    }

    private fun Response.toVideoList(): List<Video> {
        val responseBody = body?.string().orEmpty()
        val separator = "#EXT-X-STREAM-INF:"
        return responseBody.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore("\n")
                .substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, quality, videoUrl)
        }
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        }.getOrNull() ?: 0L
    }

    private fun String.getId(): String = this.substringAfterLast("/")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, null)
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
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        private const val API_URL = "https://api.puray.moe"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private val PREF_QUALITY_VALUES = arrayOf(
            "240p", "360p",
            "480p", "720p", "1080p"
        )

        private const val PREF_SHOW_ONLY_KEY = "show_only"
        private const val PREF_SHOW_ONLY_TITLE = "Mostrar apenas episódios:"
        private val ANIME_TYPES_ENTRIES = arrayOf("Todos", "Dublados", "Legendados")
        private val ANIME_TYPES_VALUES = arrayOf("", "dub", "sub")

        private const val PREF_USE_PLAYLIST_KEY = "use_m3u8_playlist"
        private const val PREF_USE_PLAYLIST_TITLE = "Usar/Ativar playlist M3U8(HLS)"

        const val PREFIX_SEARCH = "id:"
    }
}

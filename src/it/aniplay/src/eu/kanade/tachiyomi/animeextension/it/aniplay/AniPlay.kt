package eu.kanade.tachiyomi.animeextension.it.aniplay

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class AniPlay : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AniPlay"

    override val baseUrl = "https://aniplay.it"

    override val lang = "it"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeParse(response: Response): AnimesPage {
        return searchAnimeParse(response)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/anime/advanced-similar-search?page=${page - 1}&size=36&sort=views,desc&sort=id")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")

    // =============================== Search ===============================

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AniPlayFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AniPlayFilters.FilterSearchParams): Request {
        if ((filters.anni.isNotEmpty() && filters.stagione.isEmpty()) ||
            (filters.anni.isEmpty() && filters.stagione.isNotEmpty())
        ) {
            throw Exception("Per gli anime stagionali, seleziona sia l'anno che la stagione")
        }

        val url = if (filters.anni.isNotEmpty()) {
            "$baseUrl/api/seasonal-view".toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("${filters.stagione}-${filters.anni}")
                .addQueryParameter("page", (page - 1).toString())
                .addQueryParameter("size", "36")
                .addQueryParameter("sort", filters.ordina)
                .addQueryParameter("sort", "id")
        } else {
            "$baseUrl/api/anime/advanced-similar-search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("page", (page - 1).toString())
                .addQueryParameter("size", "36")
                .addQueryParameter("sort", filters.ordina)
                .addQueryParameter("sort", "id")
                .addIfNotBlank("query", query)
                .addIfNotBlank("genreIds", filters.genere)
                .addIfNotBlank("typeIds", filters.tipologia)
                .addIfNotBlank("statusIds", filters.stato)
                .addIfNotBlank("originIds", filters.origine)
                .addIfNotBlank("studioIds", filters.studio)
                .addIfNotBlank("startYear", filters.inizio)
                .addIfNotBlank("endYear", filters.fine)
        }

        return GET(url.build().toString())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<List<SearchResult>>(response.body.string())

        val animeList = parsed.map { ani ->
            SAnime.create().apply {
                title = ani.title
                if (ani.verticalImages.isNotEmpty()) {
                    thumbnail_url = ani.verticalImages.first().imageFull
                }
                url = ani.id.toString()
                description = ani.storyline
            }
        }

        return AnimesPage(animeList, animeList.size == 36)
    }

    override fun getFilterList(): AnimeFilterList = AniPlayFilters.filterList

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl/api/anime/${anime.url}")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val detailsJson = json.decodeFromString<AnimeResult>(response.body.string())
        val anime = SAnime.create()

        anime.title = detailsJson.title
        anime.author = detailsJson.studio
        anime.status = parseStatus(detailsJson.status)

        var description = detailsJson.storyline + "\n"
        description += "\nTipologia: ${detailsJson.type}"
        description += "\nOrigine: ${detailsJson.origin}"
        if (detailsJson.startDate != null) description += "\nData di inizio: ${detailsJson.startDate}"
        description += "\nStato: ${detailsJson.status}"

        anime.description = description

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl/api/anime/${anime.url}")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeJson = json.decodeFromString<AnimeResult>(response.body.string())
        val episodeList = mutableListOf<SEpisode>()

        if (animeJson.seasons.isNotEmpty()) {
            for (season in animeJson.seasons) {
                val episodesResponse = client.newCall(
                    GET("$baseUrl/api/anime/${animeJson.id}/season/${season.id}"),
                ).execute()
                val episodesJson = json.decodeFromString<List<AnimeResult.Episode>>(episodesResponse.body.string())

                for (ep in episodesJson) {
                    val episode = SEpisode.create()

                    episode.name = "Episode ${ep.episodeNumber.toIntOrNull() ?: (ep.episodeNumber.toFloatOrNull() ?: 1)} ${ep.title ?: ""}"
                    episode.episode_number = ep.episodeNumber.toFloatOrNull() ?: 0F

                    if (ep.airingDate != null) episode.date_upload = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(ep.airingDate)!!.time

                    episode.url = ep.id.toString()

                    episodeList.add(episode)
                }
            }
        } else if (animeJson.episodes.isNotEmpty()) {
            for (ep in animeJson.episodes) {
                val episode = SEpisode.create()
                episode.name = "Episode ${ep.episodeNumber.toIntOrNull() ?: (ep.episodeNumber.toFloatOrNull() ?: 1)} ${ep.title ?: ""}"
                episode.episode_number = ep.episodeNumber.toFloatOrNull() ?: 0F

                if (ep.airingDate != null) episode.date_upload = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(ep.airingDate)!!.time

                episode.url = ep.id.toString()

                episodeList.add(episode)
            }
        } else {}

        return episodeList.sortedBy { it.episode_number }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl/api/episode/${episode.url}")
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val videoJson = json.decodeFromString<VideoResult>(response.body.string())
        val videoUrl = videoJson.videoUrl

        if (videoUrl.contains(".mp4")) {
            videoList.add(
                Video(
                    videoUrl,
                    "Best (mp4)",
                    videoUrl,
                    headers = Headers.headersOf(
                        "Referer",
                        "https://aniplay.it/play/${videoJson.id}",
                    ),
                ),
            )
        } else if (videoUrl.contains(".m3u8")) {
            val masterPlaylist = client.newCall(
                GET(videoUrl, headers = Headers.headersOf("Referer", "https://aniplay.it/play/${videoJson.id}")),
            ).execute().body.string()

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                    var newUrl = it.substringAfter("\n").substringBefore("\n")

                    if (!newUrl.startsWith("http")) {
                        newUrl = videoUrl.substringBeforeLast("/") + "/" + newUrl
                    }

                    videoList.add(Video(newUrl, quality, newUrl, headers = Headers.headersOf("Referer", "https://aniplay.it/")))
                }
        } else {}

        return videoList.sort()
    }

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "In corso" -> SAnime.ONGOING
            "Completato" -> SAnime.COMPLETED
            "Sospeso" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
    }

    @Serializable
    data class VideoResult(
        val id: Int,
        val videoUrl: String,
    )

    @Serializable
    data class SearchResult(
        val id: Int,
        val title: String,
        val storyline: String,
        val verticalImages: List<Image>,
    ) {
        @Serializable
        data class Image(
            val imageFull: String,
        )
    }

    @Serializable
    data class AnimeResult(
        val id: Int,
        val title: String,
        val startDate: String? = null,
        val storyline: String,
        val type: String,
        val origin: String,
        val status: String,
        val studio: String,
        val verticalImages: List<Image>,
        val seasons: List<Season>,
        val episodes: List<Episode>,
    ) {
        @Serializable
        data class Season(
            val id: Int,
        )

        @Serializable
        data class Episode(
            val id: Int,
            val episodeNumber: String,
            val title: String? = null,
            val airingDate: String? = null,
        )

        @Serializable
        data class Image(
            val imageFull: String,
        )
    }
}

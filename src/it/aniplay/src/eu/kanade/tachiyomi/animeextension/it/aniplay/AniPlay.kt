package eu.kanade.tachiyomi.animeextension.it.aniplay

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class AniPlay : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AniPlay"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "it"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/anime/advanced-similar-search?page=${page - 1}&size=36&sort=views,desc&sort=id")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = AniPlayFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .awaitSuccess()
            .use(::searchAnimeParse)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AniPlayFilters.FilterSearchParams): Request {
        if ((filters.year.isNotEmpty() && filters.season.isEmpty()) ||
            (filters.year.isEmpty() && filters.season.isNotEmpty())
        ) {
            error("Per gli anime stagionali, seleziona sia l'anno che la stagione")
        }

        val url = if (filters.year.isNotEmpty()) {
            "$baseUrl/api/seasonal-view".toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("${filters.season}-${filters.year}")
                .addQueryParameter("page", (page - 1).toString())
                .addQueryParameter("size", "36")
                .addQueryParameter("sort", filters.order)
                .addQueryParameter("sort", "id")
        } else {
            "$baseUrl/api/anime/advanced-similar-search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("page", (page - 1).toString())
                .addQueryParameter("size", "36")
                .addQueryParameter("sort", filters.order)
                .addQueryParameter("sort", "id")
                .addIfNotBlank("query", query)
                .addIfNotBlank("genreIds", filters.genre)
                .addIfNotBlank("typeIds", filters.type)
                .addIfNotBlank("statusIds", filters.status)
                .addIfNotBlank("originIds", filters.origin)
                .addIfNotBlank("studioIds", filters.studio)
                .addIfNotBlank("startYear", filters.start)
                .addIfNotBlank("endYear", filters.end)
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

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniPlayFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/anime/${anime.url}")

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return client.newCall(animeDetailsRequestInternal(anime))
            .awaitSuccess()
            .use(::animeDetailsParse)
            .apply { initialized = true }
    }

    private fun animeDetailsRequestInternal(anime: SAnime): Request = GET("$baseUrl/api/anime/${anime.url}")

    override fun animeDetailsParse(response: Response): SAnime {
        val detailsJson = json.decodeFromString<AnimeResult>(response.body.string())

        return SAnime.create().apply {
            title = detailsJson.title
            author = detailsJson.studio
            status = parseStatus(detailsJson.status)
            description = buildString {
                append(detailsJson.storyline)
                append("\n\nTipologia: ${detailsJson.type}")
                append("\nOrigine: ${detailsJson.origin}")
                if (detailsJson.startDate != null) append("\nData di inizio: ${detailsJson.startDate}")
                append("\nStato: ${detailsJson.status}")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/api/anime/${anime.url}")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeJson = json.decodeFromString<AnimeResult>(response.body.string())
        val episodeList = mutableListOf<SEpisode>()

        if (animeJson.seasons.isNotEmpty()) {
            for (season in animeJson.seasons) {
                val episodesResponse = client.newCall(
                    GET("$baseUrl/api/anime/${animeJson.id}/season/${season.id}"),
                ).execute()
                val episodesJson = json.decodeFromString<List<AnimeResult.Episode>>(episodesResponse.body.string())

                episodeList.addAll(
                    episodesJson.map { ep ->
                        SEpisode.create().apply {
                            name = "Episode ${ep.episodeNumber.toIntOrNull() ?: (ep.episodeNumber.toFloatOrNull() ?: 1)} ${ep.title ?: ""}"
                            episode_number = ep.episodeNumber.toFloatOrNull() ?: 0F
                            if (ep.airingDate != null) date_upload = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(ep.airingDate)!!.time
                            url = ep.id.toString()
                        }
                    },
                )
            }
        } else if (animeJson.episodes.isNotEmpty()) {
            episodeList.addAll(
                animeJson.episodes.map { ep ->
                    SEpisode.create().apply {
                        name = "Episode ${ep.episodeNumber.toIntOrNull() ?: (ep.episodeNumber.toFloatOrNull() ?: 1)} ${ep.title ?: ""}"
                        episode_number = ep.episodeNumber.toFloatOrNull() ?: 0F
                        if (ep.airingDate != null) date_upload = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(ep.airingDate)!!.time
                        url = ep.id.toString()
                    }
                },
            )
        }

        return episodeList.sortedBy { it.episode_number }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/api/episode/${episode.url}")

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
                        "$baseUrl/play/${videoJson.id}",
                    ),
                ),
            )
        } else if (videoUrl.contains(".m3u8")) {
            val masterPlaylist = client.newCall(
                GET(videoUrl, headers = Headers.headersOf("Referer", "$baseUrl/play/${videoJson.id}")),
            ).execute().body.string()

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                    var newUrl = it.substringAfter("\n").substringBefore("\n")

                    if (!newUrl.startsWith("http")) {
                        newUrl = videoUrl.substringBeforeLast("/") + "/" + newUrl
                    }

                    videoList.add(Video(newUrl, quality, newUrl, headers = Headers.headersOf("Referer", "$baseUrl/")))
                }
        }

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList.sort()
    }

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "In corso" -> SAnime.ONGOING
        "Completato" -> SAnime.COMPLETED
        "Sospeso" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val PREF_DOMAIN_KEY = "preferred_domain_name_v${BuildConfig.VERSION_CODE}"
        private const val PREF_DOMAIN_TITLE = "Override BaseUrl"
        private const val PREF_DOMAIN_DEFAULT = "https://aniplay.co"
        private const val PREF_DOMAIN_SUMMARY = "For temporary uses. Updating the extension will erase this setting."

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            summary = PREF_DOMAIN_SUMMARY
            dialogTitle = PREF_DOMAIN_TITLE
            dialogMessage = "Default: $PREF_DOMAIN_DEFAULT"
            setDefaultValue(PREF_DOMAIN_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val newValueString = newValue as String
                Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, newValueString.trim()).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}

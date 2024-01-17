package eu.kanade.tachiyomi.animeextension.all.animeonsen

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListItem
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.SearchResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.VideoData
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeOnsen : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeOnsen"

    override val baseUrl = "https://animeonsen.xyz"

    private val apiUrl = "https://api.animeonsen.xyz/v4"

    override val lang = "all"

    override val supportsLatest = false

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(AOAPIInterceptor(network.client))
            .build()
    }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    override fun headersBuilder() = Headers.Builder().add("user-agent", AO_USER_AGENT)

    // ============================== Popular ===============================
    // The site doesn't have a popular anime tab, so we use the home page instead (latest anime).
    override fun popularAnimeRequest(page: Int) =
        GET("$apiUrl/content/index?start=${(page - 1) * 20}&limit=20")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = response.parseAs<AnimeListResponse>()
        val animes = responseJson.content.map { it.toSAnime() }
        // we can't (easily) serialize this thing because it returns a array with
        // two types: a boolean and a integer.
        val hasNextPage = responseJson.cursor.next.firstOrNull()?.jsonPrimitive?.boolean == true
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$apiUrl/search/$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchResult = response.parseAs<SearchResponse>().result
        val results = searchResult.map { it.toSAnime() }
        return AnimesPage(results, false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime) = GET("$apiUrl/content/${anime.url}/extensive")

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/details/${anime.url}"

    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val details = response.parseAs<AnimeDetails>()
        url = details.content_id
        title = details.content_title ?: details.content_title_en!!
        status = parseStatus(details.mal_data?.status)
        author = details.mal_data?.studios?.joinToString { it.name }
        genre = details.mal_data?.genres?.joinToString { it.name }
        description = details.mal_data?.synopsis
        thumbnail_url = "$apiUrl/image/210x300/${details.content_id}"
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime) = GET("$apiUrl/content/${anime.url}/episodes")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val contentId = response.request.url.toString().substringBeforeLast("/episodes")
            .substringAfterLast("/")
        val responseJson = response.parseAs<Map<String, EpisodeDto>>()
        return responseJson.map { (epNum, item) ->
            SEpisode.create().apply {
                url = "$contentId/video/$epNum"
                episode_number = epNum.toFloat()
                name = "Episode $epNum: ${item.name}"
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val videoData = response.parseAs<VideoData>()
        val videoUrl = videoData.uri.stream
        val subtitleLangs = videoData.metadata.subtitles
        val headers = headersBuilder().add("referer", baseUrl).build()

        val subs = videoData.uri.subtitles.sortSubs().map { (langPrefix, subUrl) ->
            val language = subtitleLangs[langPrefix]!!
            Track(subUrl, language)
        }

        val video = Video(videoUrl, "Default (720p)", videoUrl, headers, subtitleTracks = subs)
        return listOf(video)
    }

    override fun videoListRequest(episode: SEpisode) = GET("$apiUrl/content/${episode.url}")

    override fun videoUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "finished_airing" -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun AnimeListItem.toSAnime() = SAnime.create().apply {
        url = content_id
        title = content_title ?: content_title_en!!
        thumbnail_url = "$apiUrl/image/210x300/$content_id"
    }

    private fun Map<String, String>.sortSubs(): List<Map.Entry<String, String>> {
        val sub = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

        return entries.sortedWith(
            compareBy { it.key.contains(sub) },
        ).reversed()
    }
}

const val AO_USER_AGENT = "Aniyomi/app (mobile)"
private const val PREF_SUB_KEY = "preferred_subLang"
private const val PREF_SUB_TITLE = "Preferred sub language"
const val PREF_SUB_DEFAULT = "en-US"
private val PREF_SUB_ENTRIES = arrayOf(
    "العربية", "Deutsch", "English", "Español (Spain)",
    "Español (Latin)", "Français", "Italiano",
    "Português (Brasil)", "Русский",
)
private val PREF_SUB_VALUES = arrayOf(
    "ar-ME", "de-DE", "en-US", "es-ES",
    "es-LA", "fr-FR", "it-IT",
    "pt-BR", "ru-RU",
)

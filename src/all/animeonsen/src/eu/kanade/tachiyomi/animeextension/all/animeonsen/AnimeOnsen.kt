package eu.kanade.tachiyomi.animeextension.all.animeonsen

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListItem
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListResponse
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
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class AnimeOnsen : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeOnsen"

    override val baseUrl = "https://animeonsen.xyz"

    private val apiUrl = "https://api.animeonsen.xyz/v4"

    override val lang = "all"

    override val supportsLatest = false

    private val cfClient = network.cloudflareClient

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(AOAPIInterceptor(cfClient))
            .build()
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("user-agent", AO_USER_AGENT)

    // ============================== Popular ===============================
    // The site doesn't have a popular anime tab, so we use the home page instead (latest anime).
    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiUrl/content/index?start=${(page - 1) * 20}&limit=20")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<AnimeListResponse>(response.body!!.string())
        val animes = responseJson.content.map {
            it.toSAnime()
        }
        val hasNextPage = responseJson.cursor.next.firstOrNull()?.jsonPrimitive?.boolean == true
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val contentId = response.request.url.toString().substringBeforeLast("/episodes")
            .substringAfterLast("/")
        val responseJson = json.decodeFromString<JsonObject>(response.body!!.string())
        return responseJson.keys.map { epNum ->
            SEpisode.create().apply {
                url = "$contentId/video/$epNum"
                episode_number = epNum.toFloat()
                val episodeName =
                    responseJson[epNum]!!.jsonObject["contentTitle_episode_en"]!!.jsonPrimitive.content
                name = "Episode $epNum: $episodeName"
            }
        }.sortedByDescending { it.episode_number }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$apiUrl/content/${anime.url}/episodes")
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val videoData = json.decodeFromString<VideoData>(response.body!!.string())
        val videoUrl = videoData.uri.stream
        val subtitleLangs = videoData.metadata.subtitles
        val headers = Headers.headersOf(
            "referer", baseUrl,
            "user-agent", AO_USER_AGENT,
        )
        val video = try {
            val subtitles = videoData.uri.subtitles.keys.toList().sortSubs().map {
                val lang = subtitleLangs[it]!!.jsonPrimitive.content
                val url = videoData.uri.subtitles[it]!!.jsonPrimitive.content
                Track(url, lang)
            }
            Video(videoUrl, "Default (720p)", videoUrl, headers = headers, subtitleTracks = subtitles)
        } catch (e: Error) {
            Video(videoUrl, "Default (720p)", videoUrl, headers = headers)
        }
        return listOf(video)
    }

    override fun videoListRequest(episode: SEpisode) = GET("$apiUrl/content/${episode.url}")

    override fun videoUrlParse(response: Response) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchResult = json.decodeFromString<SearchResponse>(response.body!!.string()).result
        val results = searchResult.map {
            it.toSAnime()
        }
        return AnimesPage(results, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$apiUrl/search/$query")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val details = json.decodeFromString<AnimeDetails>(response.body!!.string())
        val anime = SAnime.create().apply {
            url = details.content_id
            title = details.content_title ?: details.content_title_en!!
            status = parseStatus(details.mal_data?.status)
            author = details.mal_data?.studios?.joinToString { it.name }
            genre = details.mal_data?.genres?.joinToString { it.name }
            description = details.mal_data?.synopsis
            thumbnail_url = "https://api.animeonsen.xyz/v4/image/210x300/${details.content_id}"
        }
        return anime
    }

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(GET("$apiUrl/content/${anime.url}/extensive"))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl/details/${anime.url}")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")
    override fun latestUpdatesParse(response: Response) = throw Exception("not used")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue("en-US")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(subLangPref)
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
        thumbnail_url = "https://api.animeonsen.xyz/v4/image/210x300/$content_id"
    }

    private fun List<String>.sortSubs(): List<String> {
        val language = preferences.getString(PREF_SUB_KEY, "en-US")
        val newList = mutableListOf<String>()
        var preferred = 0
        for (key in this) {
            if (key == language) {
                newList.add(preferred, key)
                preferred++
            } else {
                newList.add(key)
            }
        }
        return newList
    }
}

const val AO_USER_AGENT = "Aniyomi/app (mobile)"
private const val PREF_SUB_KEY = "preferred_subLang"
private const val PREF_SUB_TITLE = "Preferred sub language"
private val PREF_SUB_ENTRIES = arrayOf(
    "العربية", "Deutsch", "English", "Español (Spain)",
    "Español (Latin)", "Français", "Italiano",
    "Português (Brasil)", "Русский"
)
private val PREF_SUB_VALUES = arrayOf(
    "ar-ME", "de-DE", "en-US", "es-ES",
    "es-LA", "fr-FR", "it-IT",
    "pt-BR", "ru-RU"
)

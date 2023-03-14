package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AnimePahe : ConfigurableAnimeSource, AnimeHttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name = "AnimePahe"

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.use { it.asJsoup() }
        return SAnime.create().apply {
            val animeUrl = response.request.url.toString()
            setUrlWithoutDomain(animeUrl)
            title = document.selectFirst("div.title-wrapper > h1 > span")!!.text()
            author = document.selectFirst("div.col-sm-4.anime-info p:contains(Studio:)")
                ?.text()
                ?.replace("Studio: ", "")
            status = parseStatus(document.selectFirst("div.col-sm-4.anime-info p:contains(Status:) a")!!.text())
            thumbnail_url = document.selectFirst("div.anime-poster a")!!.attr("href")
            genre = document.select("div.anime-genre ul li").joinToString { it.text() }
            val synonyms = document.selectFirst("div.col-sm-4.anime-info p:contains(Synonyms:)")
                ?.text()
            description = document.select("div.anime-summary").text() +
                if (synonyms.isNullOrEmpty()) "" else "\n\n$synonyms"
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api?m=airing&page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.use { it.body.string() }
        val jObject = json.decodeFromString<JsonObject>(responseString)
        val lastPage = jObject["last_page"]!!.jsonPrimitive.int
        val page = jObject["current_page"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val animeList = jObject["data"]!!.jsonArray.map { item ->
            val itemObj = item.jsonObject
            SAnime.create().apply {
                title = itemObj["anime_title"]!!.jsonPrimitive.content
                thumbnail_url = itemObj["snapshot"]!!.jsonPrimitive.content
                val animeId = itemObj["anime_id"]!!.jsonPrimitive.int
                val session = itemObj["anime_session"]!!.jsonPrimitive.content
                setUrlWithoutDomain("$baseUrl/anime/$session?anime_id=$animeId")
                artist = itemObj["fansub"]!!.jsonPrimitive.content
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api?m=search&l=8&q=$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.use { it.body.string() }
        val jObject = json.decodeFromString<JsonObject>(responseString)
        val data = jObject["data"] ?: return AnimesPage(emptyList(), false)
        val animeList = data.jsonArray.map { item ->
            val itemObj = item.jsonObject
            SAnime.create().apply {
                title = itemObj["title"]!!.jsonPrimitive.content
                thumbnail_url = itemObj["poster"]!!.jsonPrimitive.content
                val animeId = itemObj["id"]!!.jsonPrimitive.int
                val session = itemObj["session"]!!.jsonPrimitive.content
                setUrlWithoutDomain("$baseUrl/anime/$session?anime_id=$animeId")
            }
        }
        return AnimesPage(animeList, false)
    }

    // ============================== Popular ===============================
    // This source doesnt have a popular animes page,
    // so we use latest animes page instead.
    override fun fetchPopularAnime(page: Int) = fetchLatestUpdates(page)
    override fun popularAnimeParse(response: Response): AnimesPage = TODO()
    override fun popularAnimeRequest(page: Int): Request = TODO()

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val session = anime.url.substringBefore("?anime_id=").substringAfterLast("/")
        return GET("$baseUrl/api?m=release&id=$session&sort=episode_desc&page=1")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val session = url.substringAfter("&id=").substringBefore("&")
        return recursivePages(response, session)
    }

    private fun parseEpisodePage(jsonLine: String, animeSession: String): MutableList<SEpisode> {
        val jObject = json.decodeFromString<JsonObject>(jsonLine)
        return jObject["data"]!!.jsonArray.map { item ->
            val itemObj = item.jsonObject
            SEpisode.create().apply {
                date_upload = itemObj["created_at"]!!.jsonPrimitive.content.toDate()
                val session = itemObj["session"]!!.jsonPrimitive.content
                setUrlWithoutDomain("$baseUrl/play/$animeSession/$session")
                val epNum = itemObj["episode"]!!.jsonPrimitive.float
                episode_number = epNum
                val epNumString = if (epNum % 1F == 0F) epNum.toInt().toString() else epNum.toString()
                name = "Episode $epNumString"
            }
        }.toMutableList()
    }

    private fun recursivePages(response: Response, animeSession: String): List<SEpisode> {
        val responseString = response.use { it.body.string() }
        val jObject = json.decodeFromString<JsonObject>(responseString)
        val lastPage = jObject["last_page"]!!.jsonPrimitive.int
        val page = jObject["current_page"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val returnList = parseEpisodePage(responseString, animeSession)
        if (hasNextPage) {
            val nextPage = nextPageRequest(response.request.url.toString(), page + 1)
            returnList += recursivePages(nextPage, animeSession)
        }
        return returnList
    }

    private fun nextPageRequest(url: String, page: Int): Response {
        val request = GET(url.substringBeforeLast("&page=") + "&page=$page")
        return client.newCall(request).execute()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }
        val downloadLinks = document.select("div#pickDownload > a")
        return document.select("div#resolutionMenu > button").mapIndexed { index, btn ->
            val kwikLink = btn.attr("data-src")
            val quality = btn.text()
            val paheWinLink = downloadLinks[index].attr("href")
            getVideo(paheWinLink, kwikLink, quality)
        }
    }

    private fun getVideo(paheUrl: String, kwikUrl: String, quality: String): Video {
        return if (preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)) {
            val videoUrl = KwikExtractor(client).getHlsStreamUrl(kwikUrl, referer = baseUrl)
            Video(
                videoUrl,
                quality,
                videoUrl,
                headers = Headers.headersOf("referer", "https://kwik.cx"),
            )
        } else {
            val videoUrl = KwikExtractor(client).getStreamUrlFromKwik(paheUrl)
            Video(videoUrl, quality, videoUrl)
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val subPreference = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val shouldEndWithEng = (subPreference == "eng")

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.endsWith("eng", true) == shouldEndWithEng },
            ),
        ).reversed()
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
        val domainPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
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
        }
        val linkPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LINK_TYPE_KEY
            title = PREF_LINK_TYPE_TITLE
            summary = PREF_LINK_TYPE_SUMMARY
            setDefaultValue(PREF_LINK_TYPE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(domainPref)
        screen.addPreference(subPref)
        screen.addPreference(linkPref)
    }

    // ============================= Utilities ==============================
    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        }.getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preffered_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "360p")

        private const val PREF_DOMAIN_KEY = "preffered_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private const val PREF_DOMAIN_DEFAULT = "https://animepahe.com"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animepahe.com", "animepahe.ru", "animepahe.org")
        private val PREF_DOMAIN_VALUES by lazy {
            PREF_DOMAIN_ENTRIES.map { "https://" + it }.toTypedArray()
        }

        private const val PREF_SUB_KEY = "preffered_sub"
        private const val PREF_SUB_TITLE = "Prefer subs or dubs?"
        private const val PREF_SUB_DEFAULT = "jpn"
        private val PREF_SUB_ENTRIES = arrayOf("sub", "dub")
        private val PREF_SUB_VALUES = arrayOf("jpn", "eng")

        private const val PREF_LINK_TYPE_KEY = "preffered_link_type"
        private const val PREF_LINK_TYPE_TITLE = "Use HLS links"
        private const val PREF_LINK_TYPE_DEFAULT = false
        private val PREF_LINK_TYPE_SUMMARY by lazy {
            """Enable this if you are having Cloudflare issues.
            |Note that this will break the ability to seek inside of the video unless the episode is downloaded in advance.
            """.trimMargin()
        }
    }
}

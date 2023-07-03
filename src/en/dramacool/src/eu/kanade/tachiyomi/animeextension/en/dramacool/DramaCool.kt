package eu.kanade.tachiyomi.animeextension.en.dramacool

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class DramaCool : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DramaCool"

    private val defaultBaseUrl = "https://dramacool.hr"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-popular-drama?page=$page") // page/$page

    override fun popularAnimeSelector(): String = "ul.list-episode-item li a"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        thumbnail_url = element.select("img").attr("data-original").replace(" ", "%20")
        title = element.select("h3").text()
    }

    override fun popularAnimeNextPageSelector(): String = "li.next a"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recently-added?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select("ul.switch-block a").map { element ->
            val hrefDocument = client.newCall(GET(element.attr("abs:href"))).execute().asJsoup()
            SAnime.create().apply {
                title = element.select("h3").text()
                setUrlWithoutDomain(hrefDocument.select("div.category a").attr("abs:href"))
                thumbnail_url = element.select("img").attr("data-original").replace(" ", "%20")
            }
        }
        val hasNextPage = document.select("li.next a").first() != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesSelector(): String = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("not used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("not used")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/search?keyword=$query&page=$page")

    override fun searchAnimeSelector(): String = "ul.list-episode-item li a"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        thumbnail_url = element.select("img").attr("data-original").replace(" ", "%20")
        title = element.select("h3").text()
    }

    override fun searchAnimeNextPageSelector(): String = "li.next a"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.select("div.img img").attr("alt")
        thumbnail_url = document.select("div.img img").attr("src")
        description = document.select("div.info p").text().substringAfter("Description: ").substringBefore("Country: ").substringBefore("Director: ").substringBefore("Original Network: ")
        author = document.select("div.info p:contains(Original Network) a").text()
        genre = document.select("div.info p:contains(Genre) a").joinToString(", ") { it.text() }
        status = parseStatus(document.select("div.info p:contains(Status) a").text())
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "ul.all-episode li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val epNum = element.select("h3").text().substringAfter("Episode ")

        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            name = element.select("span.type").text() + ": Episode " + element.select("h3").text().substringAfter("Episode ")
            episode_number = when {
                (epNum.isNotEmpty()) -> epNum.toFloat()
                else -> 1F
            }
            date_upload = parseDate(element.select("span.time").text())
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val iframe = document.select("iframe").attr("abs:src")
        return GET(iframe)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "ul.list-server-items li"

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val url = element.attr("data-video")
            when {
                url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                    url.contains("sbfast") || url.contains("sbfull.com") || url.contains("ssbstream.net") ||
                    url.contains("p1ayerjavseen.com") || url.contains("streamsss.net") || url.contains("sbplay2.xyz") ||
                    url.contains("sbasian.pro")
                -> {
                    val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
                    videoList.addAll(videos)
                }

                url.contains("dood") -> {
                    val video = DoodExtractor(client).videoFromUrl(url)
                    if (video != null) {
                        videoList.add(video)
                    }
                }

                url.contains("streamtape") -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(url)
                    if (video != null) {
                        videoList.add(video)
                    }
                }

                url.contains("streamtape") -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(url)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private const val RESTART_ANIYOMI = "Restart Aniyomi to apply new setting."

        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"

        private val BASE_URL_PREF = "overrideBaseUrl_v${AppInfo.getVersionName()}"

        private const val BASE_URL_PREF_SUMMARY = "For temporary uses. Update extension will erase this setting."

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF_TITLE
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            this.setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(BASE_URL_PREF, newValue as String).commit()
                    Toast.makeText(screen.context, RESTART_ANIYOMI, Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
            entryValues = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape")
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

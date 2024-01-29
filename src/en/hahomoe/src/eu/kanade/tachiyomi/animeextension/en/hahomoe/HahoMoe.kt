package eu.kanade.tachiyomi.animeextension.en.hahomoe

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class HahoMoe : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "haho.moe"

    override val baseUrl = "https://haho.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    init {
        // Save the cookie that enables thumbnails in results (popular, latest, search...)
        val httpUrl = baseUrl.toHttpUrl()
        val cookie = Cookie.parse(httpUrl, "loop-view=thumb")!!
        client.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?s=vdy-d&page=$page")

    override fun popularAnimeSelector() = "ul.anime-loop.loop > li > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        title = element.selectFirst("div.label > span, div span.thumb-title")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li.page-item a[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?s=rel-d&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = HahoMoeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val (includedTags, excludedTags, orderBy, ordering) = HahoMoeFilters.getSearchParameters(filters)

        val httpQuery = buildString {
            if (query.isNotBlank()) append(query.trim())
            if (includedTags.isNotEmpty()) {
                append(includedTags.joinToString(" genre:", prefix = " genre:"))
            }
            if (excludedTags.isNotEmpty()) {
                append(excludedTags.joinToString(" -genre:", prefix = " -genre:"))
            }
        }.let { URLEncoder.encode(it, "UTF-8") }

        return GET("$baseUrl/anime?page=$page&s=$orderBy$ordering&q=$httpQuery")
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("img.cover-image.img-thumbnail")?.absUrl("src")
        title = document.selectFirst("li.breadcrumb-item.active")!!.text()
        genre = document.select("li.genre span.value, div.genre-tree ul > li > a").joinToString { it.text() }
        description = document.selectFirst("div.card-body")?.text()
        author = document.select("li.production span.value").joinToString { it.text() }
        artist = document.selectFirst("li.group span.value")?.text()
        status = parseStatus(document.selectFirst("li.status span.value")?.text())
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.episode-loop > li > a"

    private fun episodeNextPageSelector() = popularAnimeNextPageSelector()

    override fun episodeListParse(response: Response): List<SEpisode> {
        var doc = response.asJsoup()
        return buildList {
            do {
                if (isNotEmpty()) {
                    val url = doc.selectFirst(episodeNextPageSelector())!!.absUrl("href")
                    doc = client.newCall(GET(url)).execute().asJsoup()
                }

                doc.select(episodeListSelector())
                    .map(::episodeFromElement)
                    .also(::addAll)
            } while (doc.selectFirst(episodeNextPageSelector()) != null)

            sortByDescending { it.episode_number }
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        val episodeNumberString = element.selectFirst("div.episode-number, div.episode-slug")?.text() ?: "Episode"
        episode_number = episodeNumberString.removePrefix("Episode ").toFloatOrNull() ?: 1F
        val title = element.selectFirst("div.episode-label, div.episode-title")?.text()
            ?.takeUnless { it.equals("No Title", true) }
            ?.let { ": $it" }
            .orEmpty()
        name = episodeNumberString + title
        date_upload = element.selectFirst("div.date")?.text().orEmpty().toDate()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe")!!.attr("src")
        val newHeaders = headersBuilder().set("referer", document.location()).build()
        val iframeResponse = client.newCall(GET(iframe, newHeaders)).execute()
            .asJsoup()

        return iframeResponse.select(videoListSelector()).map(::videoFromElement)
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        return Video(element.attr("src"), element.attr("title"), element.attr("src"))
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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

    // ============================= Utilities ==============================
    private fun String.toDate(): Long {
        val fixedDate = trim().replace(DATE_REGEX, "").replace("'", "")
        return runCatching { DATE_FORMATTER.parse(fixedDate)?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd 'of' MMM, yyyy", Locale.ENGLISH)
        }

        private val DATE_REGEX by lazy { Regex("(?<=\\d)(st|nd|rd|th)") }

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}

package eu.kanade.tachiyomi.animeextension.en.wcofun

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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Wcofun : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Wcofun"

    override val baseUrl = "https://www.wcofun.net"

    override val lang = "en"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers = headers)

    override fun popularAnimeSelector() = "#sidebar_right2 ul.items li"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("div.img a")!!.attr("href"))
        title = element.selectFirst("div.recent-release-episodes a")!!.text()
        thumbnail_url = element.selectFirst("div.img a img")!!.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val formBody = FormBody.Builder()
            .add("catara", query)
            .add("konuara", "series")
            .build()
        return POST("$baseUrl/search", headers, body = formBody)
    }

    override fun searchAnimeSelector() = "div#sidebar_right2 li div.img a"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.selectFirst("img")!!.run {
            thumbnail_url = attr("src")
            title = attr("alt")
        }
    }

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("div.video-title a")!!.text()
        description = document.selectFirst("div#sidebar_cat p")?.text()
        thumbnail_url = document.selectFirst("div#sidebar_cat img")!!.attr("abs:src")
        genre = document.select("div#sidebar_cat > a").joinToString { it.text() }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.cat-eps a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epName = element.ownText()
        val season = epName.substringAfter("Season ")
        val ep = epName.substringAfter("Episode ")
        val seasonNum = season.substringBefore(" ").toIntOrNull() ?: 1
        val epNum = ep.substringBefore(" ").toIntOrNull() ?: 1
        episode_number = (seasonNum * 100 + epNum).toFloat()
        name = "Season $seasonNum - Episode $epNum"
    }

    // ============================ Video Links =============================
    @Serializable
    data class VideoResponseDto(
        val server: String,
        @SerialName("enc")
        val sd: String?,
        val hd: String?,
        val fhd: String?,
    ) {
        val videos by lazy {
            listOfNotNull(
                sd?.takeIf(String::isNotBlank)?.let { Pair("SD", it) },
                hd?.takeIf(String::isNotBlank)?.let { Pair("HD", it) },
                fhd?.takeIf(String::isNotBlank)?.let { Pair("FHD", it) },
            ).map {
                val videoUrl = "$server/getvid?evid=" + it.second
                Video(videoUrl, it.first, videoUrl)
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframeLink = document.selectFirst("div.pcat-jwplayer iframe")!!.attr("src")
        val iframeDomain = "https://" + iframeLink.toHttpUrl().host

        val playerHtml = client.newCall(GET(iframeLink, headers)).execute()
            .body.string()

        val getVideoLink = playerHtml.substringAfter("\$.getJSON(\"").substringBefore("\"")

        val requestUrl = iframeDomain + getVideoLink
        val requestHeaders = headersBuilder()
            .add("x-requested-with", "XMLHttpRequest")
            .set("Referer", requestUrl)
            .set("Origin", iframeDomain)
            .build()

        val videoData = client.newCall(GET(requestUrl, requestHeaders)).execute()
            .parseAs<VideoResponseDto>()

        return videoData.videos
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality == quality },
        ).reversed()
    }

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

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("FHD", "HD", "SD")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}

package eu.kanade.tachiyomi.animeextension.id.kuramanime

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
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Kuramanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val name = "Kuramanime"

    override val baseUrl = "https://kuramanime.pro"

    override val lang = "id"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime")

    override fun popularAnimeSelector() = "div.product__item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("a > div")?.attr("data-setbg")
        title = element.selectFirst("div.product__item__text > h5")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "div.product__pagination > a:last-child"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?order_by=updated&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/anime?search=$query&page=$page")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anime__details__pic")?.attr("data-setbg")

        val details = document.selectFirst("div.anime__details__text")!!

        title = details.selectFirst("div > h3")!!.text().replace("Judul: ", "")

        val infos = details.selectFirst("div.anime__details__widget")!!
        artist = infos.select("li:contains(Studio:) > a").eachText().joinToString().takeUnless(String::isEmpty)
        status = parseStatus(infos.selectFirst("li:contains(Status:) > a")?.text())

        genre = infos.select("li:contains(Genre:) > a, li:contains(Tema:) > a, li:contains(Demografis:) > a")
            .eachText()
            .joinToString { it.trimEnd(',', ' ') }
            .takeUnless(String::isEmpty)

        description = buildString {
            details.selectFirst("p#synopsisField")?.text()?.also(::append)

            details.selectFirst("div.anime__details__title > span")?.text()
                ?.also { append("\n\nAlternative names: $it\n") }

            infos.select("ul > li").eachText().forEach { append("\n$it") }
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Sedang Tayang" -> SAnime.ONGOING
            "Selesai Tayang" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.use { it.asJsoup() }

        val html = document.selectFirst(episodeListSelector())?.attr("data-content")
            ?: return emptyList()

        val newDoc = response.asJsoup(html)

        return newDoc.select("a")
            .filterNot { it.attr("href").contains("batch") }
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeListSelector() = "a#episodeLists"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = name.filter(Char::isDigit).toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "video#player > source"

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = response.asJsoup()

        document.select("select#changeServer > option").forEach {
            videoList.addAll(
                videosFromServer(response.request.url.toString(), it.attr("value"), it.text()),
            )
        }

        return videoList.sort()
    }

    private fun videosFromServer(episodeUrl: String, server: String, name: String): List<Video> {
        val document = client.newCall(
            GET("$episodeUrl?activate_stream=1&stream_server=$server", headers = headers),
        ).execute().asJsoup()
        return document.select(videoListSelector()).map { videoFromElement(it, name, episodeUrl) }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    private fun videoFromElement(element: Element, name: String, episodeUrl: String): Video {
        var url = element.attr("src")
        if (!url.startsWith("http")) {
            url = episodeUrl + url
        }

        val quality = with(element.attr("size")) {
            when {
                contains("1080") -> "1080p"
                contains("720") -> "720p"
                contains("480") -> "480p"
                contains("360") -> "360p"
                else -> "Default"
            }
        } + " - $name"
        return Video(url, quality, url)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}

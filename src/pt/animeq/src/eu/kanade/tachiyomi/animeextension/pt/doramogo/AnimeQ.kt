package eu.kanade.tachiyomi.animeextension.pt.animeq

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeQ : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeQ"

    override val baseUrl = "https://animeq.blog"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/160679", headers)

    override fun popularAnimeSelector() = "div.widget_block:contains(Acessados) a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("a img")!!.attr("title")
        thumbnail_url = element.selectFirst("a img")?.tryGetAttr("abs:data-src", "abs:src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesSelector() = "div.ContainerEps > article.EpsItem > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("div.EpsItemImg > img")?.tryGetAttr("abs:data-src", "abs:src")
    }

    override fun latestUpdatesNextPageSelector() = "div.ContainerEps a.next.page-numbers"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page".toHttpUrl().newBuilder()
            .addPathSegment(page.toString())
            .addQueryParameter("s", query)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeSelector() = "div.ContainerEps > article.AniItem > a"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title").substringBefore(" – Todos os Epis")
        thumbnail_url = element.selectFirst("div.AniItemImg > img")?.tryGetAttr("abs:data-src", "abs:src")
    }

    override fun searchAnimeNextPageSelector() = "div.ContainerEps a.next.page-numbers"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.title().substringBefore(" – Todos os Epis")
            thumbnail_url = doc.selectFirst("#capaAnime > img")?.tryGetAttr("abs:data-src", "abs:src")
            description = doc.selectFirst("#sinopse2")?.text()

            with(doc.selectFirst("div.boxAnimeSobre")!!) {
                artist = getInfo("Estúdio")
                author = getInfo("Autor") ?: getInfo("Diretor")
                genre = getInfo("Tags")
                status = parseStatus(getInfo("Episódios"))
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return getRealDoc(response.asJsoup())
            .select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeListSelector() = "#lAnimes a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        element.selectFirst("a")!!.attr("title")
            .substringBeforeLast(" – Final")
            .substringAfterLast(" ").let {
                name = it.trim()
                episode_number = name.toFloatOrNull() ?: 1F
            }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        return document.select("div.videoBox div.aba")
            .parallelCatchingFlatMapBlocking {
                val format = document.selectFirst("a[href=#" + it.attr("id") + "]")?.text()
                    ?: "default"

                val quality = when (format) {
                    "SD" -> "360p"
                    "HD" -> "720p"
                    "FHD" -> "1080p"
                    else -> format
                }
                val iframeSrc = it.selectFirst("iframe")?.tryGetAttr("data-litespeed-src", "src")
                if (!iframeSrc.isNullOrBlank()) {
                    return@parallelCatchingFlatMapBlocking getVideosFromURL(iframeSrc, quality)
                }
                it.select("script").mapNotNull {
                    var javascript = it.attr("src")
                        ?.substringAfter(";base64,")
                        ?.substringBefore('"')
                        ?.let { String(Base64.decode(it, Base64.DEFAULT)) }

                    if (javascript.isNullOrBlank()) {
                        javascript = it.data()
                    }

                    if (javascript.isNullOrBlank() || "file:" !in javascript) {
                        return@mapNotNull null
                    }

                    val videoUrl = javascript.substringAfter("file:\"").substringBefore('"')

                    Video(videoUrl, quality, videoUrl)
                }
            }
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private fun getVideosFromURL(url: String, quality: String?): List<Video> {
        return when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            else -> emptyList()
        }
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst("div.spr.i-lista")
        if (menu != null) {
            val originalUrl = menu.parent()!!.attr("href")
            val response = client.newCall(GET(originalUrl, headers)).execute()
            return response.asJsoup()
        }

        return document
    }

    private fun parseStatus(statusString: String?): Int {
        return when {
            statusString?.trim()?.lowercase() == "em lançamento" -> SAnime.ONGOING
            statusString?.trim()?.lowercase() == "em andamento" -> SAnime.ONGOING
            statusString?.trim()?.let { REGEX_NUMBER.matches(it) } == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("div.boxAnimeSobreLinha:has(b:contains($key))")?.run {
            text()
                .substringAfter(":")
                .trim()
                .takeUnless { it.isBlank() || it == "???" }
        }
    }

    private fun Element.tryGetAttr(vararg attributeKeys: String): String? {
        val attributeKey = attributeKeys.first { hasAttr(it) }
        return attributeKey?.let { attr(attributeKey) }
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val REGEX_NUMBER by lazy { Regex("""\d+""") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("360p", "720p", "1080p")
    }
}

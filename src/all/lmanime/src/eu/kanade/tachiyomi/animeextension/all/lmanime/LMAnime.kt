package eu.kanade.tachiyomi.animeextension.all.lmanime

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.all.lmanime.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class LMAnime : ParsedAnimeHttpSource() {

    override val name = "LMAnime"

    override val baseUrl = "https://lmanime.com"

    override val lang = "all"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val ahref = element.selectFirst("h4 > a.series")!!
            setUrlWithoutDomain(ahref.attr("href"))
            title = ahref.text()
            thumbnail_url = element.selectFirst("img")!!.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector() = null

    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.serieslist.wpop-alltime li"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.asJsoup())
        return doc.select(episodeListSelector()).map(::episodeFromElement)
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            element.selectFirst("div.epl-title")!!.text().let {
                name = it
                episode_number = it.substringBefore(" (")
                    .substringAfterLast(" ")
                    .toFloatOrNull() ?: 0F
            }

            date_upload = element.selectFirst("div.epl-date")?.text().toDate()
        }
    }

    override fun episodeListSelector() = "div.eplister > ul > li > a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.selectFirst("h1.entry-title")!!.text()
            thumbnail_url = doc.selectFirst("div.thumb > img")!!.attr("src")

            val infos = doc.selectFirst("div.info-content")!!
            genre = infos.select("div.genxed > a").eachText().joinToString()
            status = parseStatus(infos.getInfo("Status"))
            artist = infos.getInfo("Studio")
            author = infos.getInfo("Fansub")

            description = buildString {
                doc.selectFirst("div.entry-content")?.text()?.let {
                    append("$it\n\n")
                }

                infos.select("div.spe > span").eachText().forEach {
                    append("$it\n")
                }
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "select.mirror > option[data-index]"

    override fun videoListParse(response: Response): List<Video> {
        val items = response.asJsoup().select(videoListSelector())
        return items.flatMap {
            val language = it.text().substringBefore(" ")
            val url = getHosterUrl(it.attr("value"))
            getVideoList(url, language)
        }
    }

    private fun getHosterUrl(encodedStr: String): String {
        return Base64.decode(encodedStr, Base64.DEFAULT)
            .let(::String) // bytearray -> string
            .substringAfter("iframe")
            .substringAfter("src=\"")
            .substringBefore('"')
            .let {
                // sometimes the url doesnt specify its protocol
                if (it.startsWith("http")) {
                    it
                } else {
                    "https:$it"
                }
            }
    }

    private fun getVideoList(url: String, language: String): List<Video> {
        return runCatching {
            when {
                "ok.ru" in url ->
                    OkruExtractor(client).videosFromUrl(url, "$language -")
                "fembed" in url ->
                    FembedExtractor(client).videosFromUrl(url, "$language -")
                "dailymotion.com" in url ->
                    DailymotionExtractor(client).videosFromUrl(url, "Dailymotion ($language)")
                else -> null
            }
        }.getOrNull() ?: emptyList()
    }

    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = "div.pagination a.next"

    override fun getFilterList() = LMAnimeFilters.filterList

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val genre = LMAnimeFilters.getGenre(filters)
            GET("$baseUrl/genres/$genre/page/$page")
        }
    }

    override fun searchAnimeSelector() = "div.listupd article a.tip"

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.selectFirst("div.tt")!!.ownText()
            thumbnail_url = element.selectFirst("img")!!.attr("src")
        }
    }

    override fun latestUpdatesNextPageSelector() = "div.hpage a:contains(Next)"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun latestUpdatesSelector() = "div.listupd.normal article a.tip"

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("div.naveps a:contains(All episodes)")?.let { link ->
            client.newCall(GET(link.attr("href")))
                .execute()
                .asJsoup()
        } ?: document
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completed" -> SAnime.COMPLETED
            "Ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(text: String): String? {
        return selectFirst("span:contains($text)")
            ?.run {
                selectFirst("a")?.text() ?: ownText()
            }
    }

    private fun String?.toDate(): Long {
        return this?.let {
            runCatching {
                DATE_FORMATTER.parse(this)?.time
            }.getOrNull()
        } ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy { SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH) }

        const val PREFIX_SEARCH = "id:"
    }
}

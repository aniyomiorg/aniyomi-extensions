package eu.kanade.tachiyomi.animeextension.pt.muitohentai

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat

class MuitoHentai : ParsedAnimeHttpSource() {
    override val name = "Muito Hentai"
    override val baseUrl = "https://www.muitohentai.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "ul.ul_sidebar > li"
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ranking-hentais/?paginacao=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            thumbnail_url = element.selectFirst("div.zeroleft > a > img")!!.attr("src")
            val a = element.selectFirst("div.lefthentais > div > b:gt(0) > a.series")!!
            url = a.attr("href")
            title = a.text()
        }
    }
    override fun popularAnimeNextPageSelector() = "div.paginacao > a:contains(»)"
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        return AnimesPage(animes, document.selectFirst(popularAnimeNextPageSelector()) != null)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "article.item"
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc
            .select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()
    }
    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val data = element.selectFirst("div.data")!!
            url = element.selectFirst("div.poster > div.season_m > a")!!.attr("href")
            name = data.selectFirst("h3")!!.text().trim()
            date_upload = parseDate(data.selectFirst("span")!!.text())
        }
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "div.playex > div#option-0 > iframe"
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val idplay = doc.selectFirst(videoListSelector())!!.attr("src").substringAfter("?idplay=")
        val res = client.newCall(GET("https://www.hentaitube.online/players_sites/mt/index.php?idplay=$idplay")).execute()
        val pdoc = res.asJsoup()
        return pdoc
            .select("source")
            .map(::videoFromElement)
            .reversed()
    }
    override fun videoFromElement(element: Element): Video {
        val url = element.attr("src")
        return Video(url, element.attr("label"), url)
    }
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException("Not used.")

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div#archive-content > article > div.poster"
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/buscar/$query")
    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.selectFirst("a")!!.attr("href")
            val img = element.selectFirst("img")!!
            title = img.attr("alt")
            thumbnail_url = img.attr("src")
        }
    }
    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException("Not used.")
    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document
            .select(searchAnimeSelector())
            .map(::searchAnimeFromElement)
        return AnimesPage(animes, false)
    }
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            val data = document.selectFirst("div.sheader > div.data")!!
            title = data.selectFirst("h1")!!.text()
            genre = data.selectFirst("div.sgeneros")!!.children()
                .filterNot { it.text().contains(title) }
                .joinToString { it.text() }
            description = data.selectFirst("div#info1 > div.wp-content > p")!!.text()
            thumbnail_url = document.selectFirst("div.sheader > div.poster > img")!!.attr("src")
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException("Not used.")
    override fun latestUpdatesSelector(): String = "div.animation-2 > article:contains(Episódio)"
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val slug = element.selectFirst("a")!!.attr("href")
                .substringAfter("/episodios/")
                .substringBefore("-episodio")
            url = "/info/$slug"
            val img = element.selectFirst("img")!!
            title = img.attr("alt")
            thumbnail_url = img.attr("src")
        }
    }
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/")
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document
            .select(latestUpdatesSelector())
            .map(::latestUpdatesFromElement)
        return AnimesPage(animes, false)
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }
    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd")
        }
    }
}

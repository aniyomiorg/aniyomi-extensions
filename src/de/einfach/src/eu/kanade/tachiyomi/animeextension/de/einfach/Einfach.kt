package eu.kanade.tachiyomi.animeextension.de.einfach

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Einfach : ParsedAnimeHttpSource() {

    override val name = "Einfach"

    override val baseUrl = "https://einfach.to"

    override val lang = "de"

    override val supportsLatest = true

    // ============================== Popular ===============================
    // Actually the source doesn't provide a popular entries page, and the
    // "sort by views" filter isn't working, so we'll use the latest series updates instead.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series/page/$page")

    override fun popularAnimeSelector() = "article.box > div.bx > a.tip"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.run {
            absUrl("data-lazy-src").ifEmpty { absUrl("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination > a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filme/page/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map(::searchAnimeByPathParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/page/$page/?s=$query")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val info = document.selectFirst("article div > div.infl")!!
        title = info.selectFirst("h1.entry-title")!!.text()
        thumbnail_url = info.selectFirst("img")?.run {
            absUrl("data-lazy-src").ifEmpty { absUrl("src") }
        }

        artist = info.getInfo("Stars:")
        genre = info.getInfo("Genre:")
        author = info.getInfo("Network:")
        status = parseStatus(info.getInfo("Status:").orEmpty())

        description = info.selectFirst("div.entry-content > p")?.ownText()
    }

    private fun Element.getInfo(label: String) =
        selectFirst("li:has(b:contains($label)) > span.colspan")?.text()?.trim()

    private fun parseStatus(status: String) = when (status) {
        "Ongoing" -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    companion object {
        const val PREFIX_SEARCH = "path:"
    }
}

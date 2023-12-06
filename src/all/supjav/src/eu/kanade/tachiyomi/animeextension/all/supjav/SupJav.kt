package eu.kanade.tachiyomi.animeextension.all.supjav

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable

class SupJav(override val lang: String = "en") : ParsedAnimeHttpSource() {

    override val name = "SupJav"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = false

    override val client = network.cloudflareClient

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl$langPath/popular/page/$page", headers)

    override fun popularAnimeSelector() = "div.posts > div.post > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        element.selectFirst("img")!!.run {
            title = attr("alt")
            thumbnail_url = absUrl("data-original").ifBlank { absUrl("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination li.active:not(:nth-last-child(2))"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =============================== Search ===============================
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
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl$langPath/?s=$query")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.content > div.post-meta")!!
        title = content.selectFirst("h2")!!.text()
        thumbnail_url = content.selectFirst("img")?.absUrl("src")

        content.selectFirst("div.cats")?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = content.select("div.tags > a").textsOrNull()
        status = SAnime.COMPLETED
    }

    private fun Elements.textsOrNull() = eachText().joinToString().takeUnless(String::isEmpty)

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
        const val PREFIX_SEARCH = "id:"
    }
}

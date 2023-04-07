package eu.kanade.tachiyomi.animeextension.all.lmanime

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
import rx.Observable

class LMAnime : ParsedAnimeHttpSource() {

    override val name = "LMAnime"

    override val baseUrl = "https://lmanime.com"

    override val lang = "all"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        TODO("Not yet implemented")
    }

    override fun popularAnimeNextPageSelector(): String? {
        TODO("Not yet implemented")
    }

    override fun popularAnimeRequest(page: Int): Request {
        TODO("Not yet implemented")
    }

    override fun popularAnimeSelector(): String {
        TODO("Not yet implemented")
    }

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        TODO("Not yet implemented")
    }

    override fun episodeListSelector(): String {
        TODO("Not yet implemented")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        TODO("Not yet implemented")
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoListSelector(): String {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        TODO("Not yet implemented")
    }

    override fun searchAnimeNextPageSelector(): String? {
        TODO("Not yet implemented")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    override fun searchAnimeSelector(): String {
        TODO("Not yet implemented")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, id)
                }
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        details.url = "/anime/$id"
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val img = element.selectFirst("img")!!
            thumbnail_url = img.attr("src")
            title = img.attr("alt")
        }
    }

    override fun latestUpdatesNextPageSelector() = "div.hpage a:contains(Next)"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun latestUpdatesSelector() = "div.listupd.normal article a.tip"

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

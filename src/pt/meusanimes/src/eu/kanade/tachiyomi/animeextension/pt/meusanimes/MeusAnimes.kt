package eu.kanade.tachiyomi.animeextension.pt.meusanimes

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

class MeusAnimes : ParsedAnimeHttpSource() {

    override val name = "Meus Animes"

    override val baseUrl = "https://meusanimes.org"

    override val lang = "pt-BR"

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
        return SAnime.create().apply {
            val infos = document.selectFirst("div.animeInfos")!!
            val right = document.selectFirst("div.right")!!

            setUrlWithoutDomain(document.location())
            title = right.selectFirst("h1")!!.text()
            genre = right.select("ul.animeGen a").joinToString(", ") { it.text() }

            thumbnail_url = infos.selectFirst("img")!!.attr("data-lazy-src")
            description = right.selectFirst("div.animeSecondContainer > p:gt(0)")!!.text()
        }
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
            client.newCall(GET("$baseUrl/animes/$id"))
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
        details.url = "/animes/$id"
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img")?.attr("data-lazy-src")
            val epUrl = element.attr("href")

            if (epUrl.substringAfterLast("/").toIntOrNull() != null) {
                setUrlWithoutDomain(epUrl.substringBeforeLast("/") + "-todos-os-episodios")
            } else { setUrlWithoutDomain(epUrl) }
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)
    override fun latestUpdatesSelector(): String = "div.ultEpsContainerItem > a"

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

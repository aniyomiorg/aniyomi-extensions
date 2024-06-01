package eu.kanade.tachiyomi.animeextension.pt.animescx

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesCX : ParsedAnimeHttpSource() {

    override val name = "Animes CX"

    override val baseUrl = "https://animescx.com.br"

    override val lang = "pt-BR"

    override val supportsLatest = false

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/doramas-legendados/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(popularAnimeSelector()).map(::popularAnimeFromElement)

        val hasNextPage = doc.selectFirst("a.rl_anime_pagination:last-child")
            ?.let { it.attr("href").getPage() != doc.location().getPage() }
            ?: false

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeSelector() = "div.listaAnimes_Riverlab_Container > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.infolistaAnimes_RiverLab")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
            .apply { setUrlWithoutDomain(response.request.url.toString()) }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        throw UnsupportedOperationException()
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException()
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

    // ============================= Utilities ==============================
    private fun String.getPage() = substringAfterLast("/page/").substringBefore("/")

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

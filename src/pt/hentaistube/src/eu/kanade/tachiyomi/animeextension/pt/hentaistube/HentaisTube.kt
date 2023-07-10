package eu.kanade.tachiyomi.animeextension.pt.hentaistube

import eu.kanade.tachiyomi.animeextension.pt.hentaistube.HentaisTubeFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.pt.hentaistube.dto.ItemsListDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy

class HentaisTube : ParsedAnimeHttpSource() {

    override val name = "HentaisTube"

    override val baseUrl = "https://www.hentaistube.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ranking-hentais?paginacao=$page", headers)

    override fun popularAnimeSelector() = "ul.ul_sidebar > li"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        element.selectFirst("div.rt a.series")!!.also {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text().substringBefore(" - Episódios")
        }
    }

    override fun popularAnimeNextPageSelector() = "div.paginacao > a:contains(»)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesSelector() = "div.epiContainer:first-child div.epiItem > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href").substringBeforeLast("-") + "s")
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    private val animeList by lazy {
        val headers = headersBuilder().add("X-Requested-With", "XMLHttpRequest").build()
        client.newCall(GET("$baseUrl/json-lista-capas.php", headers)).execute()
            .use { it.body.string() }
            .let { json.decodeFromString<ItemsListDto>(it) }
            .items
            .asSequence()
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            val params = HentaisTubeFilters.getSearchParameters(filters).apply {
                animeName = query
            }
            val filtered = animeList.applyFilterParams(params)
            val results = filtered.chunked(30).toList()
            val hasNextPage = results.size > page
            val currentPage = if (results.size == 0) {
                emptyList<SAnime>()
            } else {
                results.get(page - 1).map {
                    SAnime.create().apply {
                        title = it.title.substringBefore("- Episódios")
                        url = "/" + it.url
                        thumbnail_url = it.thumbnail
                    }
                }
            }
            Observable.just(AnimesPage(currentPage, hasNextPage))
        }
    }

    override fun getFilterList(): AnimeFilterList = HentaisTubeFilters.FILTER_LIST

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val infos = document.selectFirst("div#anime")!!
        thumbnail_url = infos.selectFirst("img")!!.attr("src")
        title = infos.getInfo("Hentai:")
        genre = infos.getInfo("Tags")
        artist = infos.getInfo("Estúdio")
        description = infos.selectFirst("div#sinopse2")?.text().orEmpty()
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

    // ============================= Utilities ==============================
    private fun Element.getInfo(key: String): String =
        select("div.boxAnimeSobreLinha:has(b:contains($key)) > a")
            .eachText()
            .joinToString()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

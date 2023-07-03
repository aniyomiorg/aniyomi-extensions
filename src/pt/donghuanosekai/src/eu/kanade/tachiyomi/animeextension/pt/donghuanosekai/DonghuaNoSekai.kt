package eu.kanade.tachiyomi.animeextension.pt.donghuanosekai

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class DonghuaNoSekai : ParsedAnimeHttpSource() {

    override val name = "Donghua No Sekai"

    override val baseUrl = "https://donghuanosekai.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.sidebarContent div.navItensTop li > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos?pagina=$page", headers)

    override fun latestUpdatesSelector() = "div.boxContent div.itemE > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.title h3")!!.text()
        thumbnail_url = element.selectFirst("div.thumb img")!!.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = "ul.content-pagination > li.next"

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
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    @Serializable
    data class SearchResponseDto(
        val results: List<String>,
        val page: Int,
        val total_page: Int = 1,
    )

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/donghuas", headers)).execute()
            .use { it.asJsoup() }
            .selectFirst("div.menu_filter_box")!!
            .attr("data-secury")
    }

    override fun getFilterList() = DonghuaNoSekaiFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DonghuaNoSekaiFilters.getSearchParameters(filters)
        val body = FormBody.Builder().apply {
            add("type", "lista")
            add("action", "getListFilter")
            add("limit", "30")
            add("token", searchToken)
            add("search", query.ifBlank { "0" })
            add("pagina", "$page")
            val filterData = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("filter_animation", params.animation)
                addQueryParameter("filter_audio", "undefined")
                addQueryParameter("filter_letter", params.letter)
                addQueryParameter("filter_order", params.orderBy)
                addQueryParameter("filter_status", params.status)
                addQueryParameter("type_url", "ONA")
            }.build().encodedQuery

            val genres = params.genres.joinToString { "\"$it\"" }
            val delgenres = params.deleted_genres.joinToString { "\"$it\"" }

            add("filters", """{"filter_data": "$filterData", "filter_genre_add": [$genres], "filter_genre_del": [$delgenres]}""")
        }.build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", body = body, headers = headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return runCatching {
            val data = response.parseAs<SearchResponseDto>()
            val animes = data.results.map(Jsoup::parse)
                .mapNotNull { it.selectFirst(searchAnimeSelector()) }
                .map(::searchAnimeFromElement)
            val hasNext = data.total_page > data.page
            AnimesPage(animes, hasNext)
        }.getOrElse { AnimesPage(emptyList(), false) }
    }

    override fun searchAnimeSelector() = "div.itemE > a"

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        throw UnsupportedOperationException("Not used.")
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
    private inline fun <reified T> Response.parseAs(): T {
        return use { it.body.string() }.let(json::decodeFromString)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

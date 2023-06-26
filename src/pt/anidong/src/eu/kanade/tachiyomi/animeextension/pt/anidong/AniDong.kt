package eu.kanade.tachiyomi.animeextension.pt.anidong

import eu.kanade.tachiyomi.animeextension.pt.anidong.dto.SearchResultDto
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy

class AniDong : ParsedAnimeHttpSource() {

    override val name = "AniDong"

    override val baseUrl = "https://anidong.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)
    override fun popularAnimeSelector() = "article.top10_animes_item > a"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used.")
    }

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchData: SearchResultDto = response.use { it.body.string() }
            .takeIf { it.trim() != "402" }
            ?.let(json::decodeFromString)
            ?: return AnimesPage(emptyList<SAnime>(), false)

        val animes = searchData.animes.map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.url)
                title = it.title
                thumbnail_url = it.thumbnail_url
            }
        }

        val hasNextPage = searchData.pages > 1 && searchData.animes.size == 10

        return AnimesPage(animes, hasNextPage)
    }

    override fun getFilterList() = AniDongFilters.FILTER_LIST

    private val nonce by lazy {
        client.newCall(GET("$baseUrl/?js_global=1&ver=6.2.2")).execute()
            .use { it.body.string() }
            .substringAfter("search_nonce")
            .substringAfter("'")
            .substringBefore("'")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniDongFilters.getSearchParameters(filters)

        val body = FormBody.Builder()
            .add("letra", "")
            .add("action", "show_animes_ajax")
            .add("nome", query)
            .add("status", params.status)
            .add("formato", params.format)
            .add("search_nonce", nonce)
            .add("paged", page.toString())
            .apply {
                params.genres.forEach { add("generos[]", it) }
            }.build()

        val newHeaders = headersBuilder() // sets user-agent
            .add("Referer", baseUrl)
            .add("x-requested-with", "XMLHttpRequest")
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", headers = newHeaders, body = body)
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
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
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "div.paginacao > a.next"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page/")

    override fun latestUpdatesSelector() = "article.main_content_article > a"

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

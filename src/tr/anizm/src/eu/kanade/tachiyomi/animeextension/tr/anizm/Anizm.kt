package eu.kanade.tachiyomi.animeextension.tr.anizm

import eu.kanade.tachiyomi.animeextension.tr.anizm.AnizmFilters.applyFilterParams
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy

class Anizm : ParsedAnimeHttpSource() {

    override val name = "Anizm"

    override val baseUrl = "https://anizm.net"

    override val lang = "tr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.popularAnimeCarousel a.slideAnimeLink"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst(".title")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        element.attr("href")
            .substringBefore("-bolum-izle")
            .substringBeforeLast("-")
            .also { setUrlWithoutDomain(it) }
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime-izle?sayfa=$page", headers)

    override fun latestUpdatesSelector() = "div#episodesMiddle div.posterBlock > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "div.nextBeforeButtons > div.ui > a.right:not(.disabled)"

    // =============================== Search ===============================
    private val animeList by lazy {
        client.newCall(GET("$baseUrl/getAnimeListForSearch", headers)).execute()
            .parseAs<List<SearchItemDto>>()
            .asSequence()
    }

    override fun getFilterList(): AnimeFilterList = AnizmFilters.FILTER_LIST

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            val params = AnizmFilters.getSearchParameters(filters).apply {
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
                        title = it.title
                        url = "/" + it.slug
                        thumbnail_url = baseUrl + "/storage/pcovers/" + it.thumbnail
                    }
                }
            }
            Observable.just(AnimesPage(currentPage, hasNextPage))
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
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
    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromStream(it.body.byteStream())
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

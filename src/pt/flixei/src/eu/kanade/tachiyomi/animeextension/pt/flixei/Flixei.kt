package eu.kanade.tachiyomi.animeextension.pt.flixei

import eu.kanade.tachiyomi.animeextension.pt.flixei.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.flixei.dto.ApiResultsDto
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy

class Flixei : ParsedAnimeHttpSource() {

    override val name = "Flixei"

    override val baseUrl = "https://flixei.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val body = "slider=3".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        return POST("$baseUrl/includes/ajax/home.php", body = body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val results = response.parseAs<ApiResultsDto<AnimeDto>>()
        val animes = results.items.values.map(::parseAnimeFromObject)
        return AnimesPage(animes, false)
    }

    private fun parseAnimeFromObject(anime: AnimeDto): SAnime {
        return SAnime.create().apply {
            title = anime.title
            setUrlWithoutDomain("/assistir/filme/${anime.url}/online/gratis")
            thumbnail_url = "$baseUrl/content/movies/posterPt/185/${anime.id}.webp"
        }
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun popularAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    override fun popularAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used.")
    }

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            thumbnail_url = document.selectFirst("meta[property=og:image]")!!.attr("content")
            val container = document.selectFirst("div.moviePresent")!!
            with(container) {
                title = selectFirst("h2.tit")!!.text()
                genre = select("div.genres > span").eachText().joinToString()
                author = getInfo("Diretor")
                artist = getInfo("Produtoras")
                description = buildString {
                    selectFirst("p")?.text()?.let { append(it + "\n\n") }
                    getInfo("Título")?.let { append("Título original: $it\n") }
                    getInfo("Serie de")?.let { append("ano: $it\n") }
                    getInfo("Elenco")?.let { append("Elenco: $it\n") }
                    getInfo("Qualidade")?.let { append("Qualidade: $it\n") }
                }
            }
        }
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
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun searchAnimeNextPageSelector() = null

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/pesquisar/$query")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/assistir/$path/online/gratis"))
                .asObservableSuccess()
                .map(::searchAnimeByPathParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("div.i span")!!.text()
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            setUrlWithoutDomain("/" + element.attr("href"))
        }
    }

    override fun latestUpdatesNextPageSelector() = "div.paginationSystem a.next"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filmes/estreia/$page")

    override fun latestUpdatesSelector() = "div.generalMoviesList > a.gPoster"

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        return body.string().let(json::decodeFromString)
    }

    private fun Element.getInfo(item: String) = selectFirst("*:containsOwn($item) b")?.text()

    companion object {
        const val PREFIX_SEARCH = "path:"
    }
}

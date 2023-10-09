package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi

import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.RapidrameExtractor
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.VidmolyExtractor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy

class HDFilmCehennemi : ParsedAnimeHttpSource() {

    override val name = "HDFilmCehennemi"

    override val baseUrl = "https://www.hdfilmcehennemi.life"

    override val lang = "tr"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en-cok-begenilen-filmleri-izle/page/$page/")

    override fun popularAnimeSelector() = "div.row div.poster > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h2.title")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("data-src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li > a[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page/")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val headers = headersBuilder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = FormBody.Builder().add("query", query).build()

        return POST("$baseUrl/search/", headers, body)
    }

    @Serializable
    data class SearchResponse(val result: List<MovieDto>)

    @Serializable
    data class MovieDto(val title: String, val poster: String, val slug: String)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<SearchResponse>()
        val movies = data.result.map {
            SAnime.create().apply {
                title = it.title
                thumbnail_url = "$baseUrl/uploads/poster/" + it.poster
                url = "/" + it.slug
            }
        }

        return AnimesPage(movies, false)
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
        status = SAnime.COMPLETED

        val div = document.selectFirst("div.card-body > div.row")!!

        div.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt")
        }

        genre = div.select("div > a[href*=tur/]").eachText().joinToString().takeIf(String::isNotEmpty)
        artist = div.select("a.chip[href*=oyuncu/]").eachText().joinToString().takeIf(String::isNotEmpty)

        description = div.selectFirst("article > p")?.text()
    }

    // ============================== Episodes ==============================
    override fun fetchEpisodeList(anime: SAnime) = Observable.just(
        listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Movie"
            },
        ),
    )

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================ Video Links =============================
    private val vidmolyExtractor by lazy { VidmolyExtractor(client, headers) }
    private val rapidrameExtractor by lazy { RapidrameExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }

        return doc.select("div.card-body > nav > a:not([href=#])")
            .drop(1)
            .parallelMap { client.newCall(GET(it.attr("href") + "/")).execute().use { it.asJsoup() } }
            .let { listOf(doc) + it }
            .mapNotNull { it.selectFirst("div.card-video > iframe")?.attr("data-src") }
            .filter(String::isNotBlank)
            .parallelMap { url ->
                runCatching {
                    when {
                        url.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(url)
                        url.contains("$baseUrl/playerr") -> rapidrameExtractor.videosFromUrl(url)
                        else -> emptyList<Video>()
                    }
                }.getOrNull() ?: emptyList<Video>()
            }.flatten().ifEmpty { throw Exception("No videos available xD") }
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

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

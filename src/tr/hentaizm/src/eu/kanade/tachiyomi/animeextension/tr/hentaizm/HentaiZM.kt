package eu.kanade.tachiyomi.animeextension.tr.hentaizm

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class HentaiZM : ParsedAnimeHttpSource() {

    override val name = "HentaiZM"

    override val baseUrl = "https://www.hentaizm.fun"

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    init {
        runBlocking {
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("user", "demo")
                    .add("pass", "demo") // peak security
                    .add("redirect_to", baseUrl)
                    .build()

                val headers = headersBuilder()
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()

                client.newCall(POST("$baseUrl/giris", headers, body)).execute()
                    .close()
            }
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en-cok-izlenenler/page/$page", headers)

    override fun popularAnimeParse(response: Response) =
        super.popularAnimeParse(response).let { page ->
            val animes = page.animes.distinctBy { it.url }
            AnimesPage(animes, page.hasNextPage)
        }

    override fun popularAnimeSelector() = "div.moviefilm"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst("div.movief > a")!!.text()
            .substringBefore(". Bölüm")
            .substringBeforeLast(" ")
        element.selectFirst("img")!!.attr("abs:src").let {
            thumbnail_url = it
            val slug = it.substringAfterLast("/").substringBefore(".")
            setUrlWithoutDomain("/hentai-detay/$slug")
        }
    }

    override fun popularAnimeNextPageSelector() = "span.current + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/yeni-eklenenler?c=${page - 1}", headers)

    override fun latestUpdatesParse(response: Response) =
        super.latestUpdatesParse(response).let { page ->
            val animes = page.animes.distinctBy { it.url }
            AnimesPage(animes, page.hasNextPage)
        }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "a[rel=next]:contains(Sonraki Sayfa)"

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/hentai-detay/$id"))
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
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeSelector() = throw UnsupportedOperationException("Not used.")

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException("Not used.")

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val content = document.selectFirst("div.filmcontent")!!
        title = content.selectFirst("h1")!!.text()
        thumbnail_url = content.selectFirst("img")!!.attr("abs:src")
        genre = content.select("tr:contains(Hentai Türü) > td > a").eachText().joinToString()
        description = content.selectFirst("tr:contains(Özet) + tr > td")
            ?.text()
            ?.takeIf(String::isNotBlank)
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

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

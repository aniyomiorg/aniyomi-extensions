package eu.kanade.tachiyomi.animeextension.pt.openanimes

import eu.kanade.tachiyomi.animeextension.pt.openanimes.extractors.BloggerExtractor
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
import java.text.SimpleDateFormat
import java.util.Locale

class OpenAnimes : ParsedAnimeHttpSource() {

    override val name = "Open Animes"

    override val baseUrl = "https://openanimes.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun popularAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    override fun popularAnimeRequest(page: Int): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun popularAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).reversed()

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val title = element.selectFirst("div.tituloEP > h3")!!.text().trim()
            name = title
            date_upload = element.selectFirst("span.data")?.text().toDate()
            episode_number = title.substringAfterLast(" ").toFloatOrNull() ?: 0F
        }
    }

    override fun episodeListSelector() = "div.listaEp div.episodioItem > a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            artist = doc.getInfo("Estúdio")
            author = doc.getInfo("Autor") ?: doc.getInfo("Diretor")
            genre = doc.select("div.info span.cat > a").eachText().joinToString()
            title = doc.selectFirst("div.tituloPrincipal > h1")!!.text()
                .removePrefix("Assistir ")
                .removeSuffix(" Temporada Online")
            thumbnail_url = doc.selectFirst("div.thumb > img")!!.attr("data-lazy-src")

            val statusStr = doc.selectFirst("li:contains(Status) > span[data]")?.text()
            status = when (statusStr) {
                "Completo" -> SAnime.COMPLETED
                "Lançamento" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val playerUrl = response.use { it.asJsoup() }
            .selectFirst("div.Link > a")
            ?.attr("href") ?: return emptyList()

        return client.newCall(GET(playerUrl, headers)).execute()
            .use {
                val iframeUrl = it.asJsoup().selectFirst("iframe")?.attr("src")
                    ?: return emptyList()
                BloggerExtractor(client).videosFromUrl(iframeUrl, headers)
            }
    }
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$id"))
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
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            element.selectFirst("a.thumb")!!.let {
                setUrlWithoutDomain(it.attr("href"))
                thumbnail_url = it.selectFirst("img")!!.attr("data-lazy-src")
            }
            title = element.selectFirst("h3 > a")!!.text()
        }
    }

    override fun latestUpdatesNextPageSelector() = "div.pagination a.pagination__arrow--right"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page")

    override fun latestUpdatesSelector() = "div.contents div.itens > div"

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("a:has(i.fa-grid)")?.let { link ->
            client.newCall(GET(link.attr("href")))
                .execute()
                .asJsoup()
        } ?: document
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("div.info li:has(span:containsOwn($key))")
            ?.ownText()
            ?.trim()
    }

    private fun String?.toDate(): Long {
        return this?.let {
            runCatching {
                DATE_FORMATTER.parse(this)?.time
            }.getOrNull()
        } ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
        }

        const val PREFIX_SEARCH = "id:"
    }
}

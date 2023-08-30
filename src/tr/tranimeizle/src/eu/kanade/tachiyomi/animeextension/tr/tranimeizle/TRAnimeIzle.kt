package eu.kanade.tachiyomi.animeextension.tr.tranimeizle

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

class TRAnimeIzle : ParsedAnimeHttpSource() {

    override val name = "TR Anime Izle"

    override val baseUrl = "https://www.tranimeizle.co"

    override val lang = "tr"

    override val supportsLatest = true

    override val client by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(ShittyCaptchaInterceptor(baseUrl, headers))
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/listeler/populer/sayfa-$page")

    override fun popularAnimeSelector() = "div.post-body div.flx-block"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("data-href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        title = element.selectFirst("div.bar > h4")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li:has(.ti-angle-right):not(.disabled)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/listeler/yenibolum/sayfa-$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) =
        popularAnimeFromElement(element).apply {
            // Convert episode url to anime url
            url = "/anime$url".substringBefore("-bolum").substringBeforeLast("-") + "-izle"
        }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
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
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/arama/$query?page=$page")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("div.playlist-title h1")!!.text()
        thumbnail_url = document.selectFirst("div.poster .social-icon img")!!.attr("src")

        val infosDiv = document.selectFirst("div.col-md-6 > div.row")!!
        genre = infosDiv.select("div > a.genre").eachText().joinToString()
        author = infosDiv.select("dd:contains(Fansublar) + dt a").eachText().joinToString()

        description = buildString {
            document.selectFirst("div.p-10 > p")?.text()?.also(::append)

            var dtCount = 0 // AAAAAAAA I HATE MUTABLE VALUES
            infosDiv.select("dd, dt").forEach {
                // Ignore non-wanted info
                it.selectFirst("dd:contains(Puanlama), dd:contains(Anime Türü), dt:has(i.fa-star), dt:has(a.genre)")
                    ?.let { return@forEach }

                val text = it.text()
                // yes
                when (it.tagName()) {
                    "dd" -> {
                        append("\n$text: ")
                        dtCount = 0
                    }
                    "dt" -> {
                        if (dtCount == 0) {
                            append(text)
                        } else {
                            append(", $text")
                        }
                        dtCount++
                    }
                }
            }
        }
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

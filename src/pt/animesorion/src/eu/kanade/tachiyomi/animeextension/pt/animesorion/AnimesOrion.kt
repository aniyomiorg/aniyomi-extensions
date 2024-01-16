package eu.kanade.tachiyomi.animeextension.pt.animesorion

import eu.kanade.tachiyomi.animeextension.pt.animesorion.extractors.LinkfunBypasser
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesOrion : ParsedAnimeHttpSource() {

    override val name = "Animes Órion"

    override val baseUrl = "https://animesorion.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.tab-content-block article > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h2.ttl")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lista-de-episodios?page=$page")

    override fun latestUpdatesSelector() = "div.mv-list > article"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.lnk-blk")!!.attr("href"))
        title = element.selectFirst("h2")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = "nav.pagination > a.next"

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
        return AnimesPage(listOf(details), false)
    }

    override fun getFilterList() = AnimesOrionFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimesOrionFilters.getSearchParameters(filters)
        val url = "$baseUrl/animes".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("tipo", params.type)
            addQueryParameter("genero", params.genre)
            addQueryParameter("status", params.status)
            addQueryParameter("letra", params.letter)
            addQueryParameter("audio", params.audio)
            addQueryParameter("ano", params.year)
            addQueryParameter("temporada", params.season)
        }.build().toString()

        return GET(url, headers)
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())
        thumbnail_url = doc.selectFirst("img.lnk-blk")?.attr("src")

        val infos = doc.selectFirst("header.hd > div.rght")!!
        title = infos.selectFirst("h2.title")!!.text()
        genre = infos.select(">a").eachText().joinToString()
        status = parseStatus(infos.selectFirst("span.tag + a")?.text())

        description = buildString {
            infos.selectFirst("h2.ttl")?.text()
                ?.takeIf(String::isNotBlank)
                ?.also { append("Títulos alternativos: $it\n\n") }

            doc.select("div.entry > p").eachText().forEach {
                append("$it\n")
            }
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status?.trim()) {
            "Em Lançamento" -> SAnime.ONGOING
            "Finalizado" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "article.epsd > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val num = element.selectFirst("span.ttl")!!.text()
        episode_number = num.toFloatOrNull() ?: 1F
        name = "Episódio $num"
        scanlator = element.selectFirst("span.pdx")?.text() ?: "Leg"
    }

    override fun episodeListParse(response: Response) =
        super.episodeListParse(response)
            .sortedWith(
                compareBy(
                    { it.scanlator != "Leg" }, // Dub first
                    { it.episode_number },
                ),
            ).reversed()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val url = doc.selectFirst("div.rvwf > a")!!.attr("href")
        val bypasser = LinkfunBypasser(client)
        return client.newCall(GET(url, headers))
            .execute()
            .use(bypasser::getIframeResponse)
            .use(::extractVideoFromResponse)
            .let(::listOf)
    }

    private fun extractVideoFromResponse(response: Response): Video {
        val decodedBody = LinkfunBypasser.decodeAtob(response.use { it.body.string() })
        val url = decodedBody
            .substringAfter("sources")
            .substringAfter("file: \"")
            .substringBefore('"')
        val videoHeaders = Headers.headersOf("Referer", response.request.url.toString())
        return Video(url, "default", url, videoHeaders)
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
    private fun getRealDoc(document: Document): Document {
        if (!document.location().contains("/episodio/")) return document

        return document.selectFirst("div.epsdsnv > a:has(i.fa-indent)")?.let {
            client.newCall(GET(it.attr("href"), headers)).execute()
                .use { req -> req.asJsoup() }
        } ?: document
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

package eu.kanade.tachiyomi.animeextension.pt.animesaria

import eu.kanade.tachiyomi.animeextension.pt.animesaria.extractors.LinkfunBypasser
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

class AnimesAria : ParsedAnimeHttpSource() {

    override val name = "Animes Aria"

    override val baseUrl = "https://animesaria.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/novos/animes?page=$page")

    override fun popularAnimeSelector() = "div.item > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/novos/episodios?page=$page")

    override fun latestUpdatesSelector() = "div.item > div.pos-rlt"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")?.attr("src")
        val ahref = element.selectFirst("a")!!
        title = ahref.attr("title")
        setUrlWithoutDomain(ahref.attr("href").substringBefore("/episodio/"))
    }

    override fun latestUpdatesNextPageSelector() = "a:containsOwn(Próximo):not(.disabled)"

    // =============================== Search ===============================
    override fun getFilterList() = AnimesAriaFilters.FILTER_LIST

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
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimesAriaFilters.getSearchParameters(filters)
        val url = "$baseUrl/anime/buscar".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("tipo", params.type)
            .addQueryParameter("genero", params.genre)
            .addQueryParameter("status", params.status)
            .addQueryParameter("letra", params.letter)
            .addQueryParameter("audio", params.audio)
            .addQueryParameter("ano", params.year)
            .addQueryParameter("temporada", params.season)
            .build()
        return GET(url.toString())
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val row = document.selectFirst("div.anime_background_w div.row")!!
        title = row.selectFirst("h1 > span")!!.text()
        status = row.selectFirst("div.clear span.btn")?.text().toStatus()
        thumbnail_url = document.selectFirst("link[as=image]")?.attr("href")
        genre = row.select("div.clear a.btn").eachText().joinToString()

        description = buildString {
            document.selectFirst("li.active > small")!!
                .ownText()
                .substringAfter(": ")
                .also(::append)

            append("\n\n")

            row.selectFirst("h1 > small")?.text()?.also {
                append("Títulos Alternativos: $it\n")
            }

            // Additional info
            row.select("div.pull-right > a").forEach {
                val title = it.selectFirst("small")!!.text()
                val value = it.selectFirst("span")!!.text()
                append("$title: $value\n")
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        element.parent()!!.selectFirst("a > b")!!.ownText().also {
            name = it
            episode_number = it.substringAfter(" ").toFloatOrNull() ?: 0F
        }
        setUrlWithoutDomain(element.attr("href"))
        scanlator = element.text().substringAfter(" ") // sub/dub
    }

    override fun episodeListSelector() = "td div.clear > a.btn-xs"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val formUrl = document.selectFirst("center > a.btn")!!.attr("href")
        val bypasser = LinkfunBypasser(client)
        return client.newCall(GET(formUrl, headers))
            .execute()
            .use(bypasser::getIframeResponse)
            .use(::extractVideoFromResponse)
            .let(::listOf)
    }

    private fun extractVideoFromResponse(response: Response): Video {
        val decodedBody = LinkfunBypasser.decodeAtob(response.body.string())
        val url = decodedBody
            .substringAfter("sources")
            .substringAfter("file: \"")
            .substringBefore('"')
        val videoHeaders = Headers.headersOf("Referer", response.request.url.toString())
        return Video(url, "default", url, videoHeaders)
    }

    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoListSelector(): String {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // ============================= Utilities ==============================
    private fun String?.toStatus() = when (this) {
        "Finalizado" -> SAnime.COMPLETED
        "Lançamento" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

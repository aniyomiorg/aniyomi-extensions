package eu.kanade.tachiyomi.animeextension.pt.listadeanimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ListaDeAnimes : ParsedAnimeHttpSource() {
    override val name = "Lista de Animes"

    override val baseUrl = "https://www.listadeanimes.com"

    override val lang = "pt-BR"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun popularAnimeSelector() = "article.post.excerpt > div.capa:not(:has(a[href=$baseUrl/anime-lista-online]))"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        val img = element.selectFirst("img")!!
        title = titleCase(img.attr("title").substringBefore(" todos os episódios"))
        thumbnail_url = img.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/page/$page?s=$query")
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val titleText = document.selectFirst("h1.title.single-title")!!.text()
        title = titleCase(titleText.substringBefore(" todos os episódios"))
        thumbnail_url = document.selectFirst("img.aligncenter.size-full")?.attr("src")
        val infos = document.selectFirst("div#content.post-single-content > center")
        val infosText = infos?.run {
            html()
                .replace("<br>", "\n")
                .replace("<b>", "")
                .replace("</b>", "")
        }?.let { "\n\n$it" }.orEmpty()

        val sinopse = document.selectFirst("div#content > *:contains(Sinopse)")?.nextElementSibling()
        description = (sinopse?.text() ?: "Sem sinopse.") + infosText
        genre = document.select("a[rel=tag]").joinToString { it.text() }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.videos > ul"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select("div.videos > ul > li:gt(0)")
            .map(::episodeFromElement)
            .reversed()
    }
    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            episode_number = runCatching {
                element.selectFirst("string")!!
                    .text()
                    .substringAfter(" ")
                    .toFloat()
            }.getOrDefault(0F)
            name = element.text().substringAfter("► ")
            url = element.attr("id")
        }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return listOf(Video(episode.url, episode.name, episode.url))
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response) = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun titleCase(str: String): String {
        return str.split(' ')
            .map { it.replaceFirstChar(Char::uppercase) }
            .joinToString(" ")
    }
}

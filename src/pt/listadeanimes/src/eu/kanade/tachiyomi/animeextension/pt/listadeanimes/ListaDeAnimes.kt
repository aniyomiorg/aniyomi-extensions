package eu.kanade.tachiyomi.animeextension.pt.listadeanimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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
import rx.Observable

class ListaDeAnimes : ParsedAnimeHttpSource() {
    override val name = "Lista de Animes"
    override val baseUrl = "https://www.listadeanimes.com"
    override val lang = "pt-BR"
    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.post.excerpt > div.capa:not(:has(a[href=https://www.listadeanimes.com/anime-lista-online]))"
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            val img = element.selectFirst("img")!!
            title = titleCase(img.attr("title").substringBefore(" todos os episódios"))
            thumbnail_url = img.attr("data-src")
            initialized = false
        }
    }
    override fun popularAnimeNextPageSelector() = "a.next.page-numbers"
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        return AnimesPage(animes, document.selectFirst(searchAnimeNextPageSelector()) != null)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.videos > ul"
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
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        return Observable.just(listOf(Video(episode.url, episode.name, episode.url)))
    }

    override fun videoListSelector() = throw UnsupportedOperationException("Not used.")
    override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException("Not used.")
    override fun videoListParse(response: Response) = throw UnsupportedOperationException("Not used.")
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException("Not used.")
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException("Not used.")

    // =============================== Search ===============================
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page?s=$query")
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            val titleText = document.selectFirst("h1.title.single-title")!!.text()
            title = titleCase(titleText.substringBefore(" todos os episódios"))
            thumbnail_url = document.selectFirst("img.aligncenter.size-full")!!.attr("src")
            val infos = document.selectFirst("div#content.post-single-content > center")
            var infosText: String? = null
            if (infos != null) {
                infosText = infos.html()
                    .replace("<br>", "\n")
                    .replace("<b>", "")
                    .replace("</b>", "")
            }
            val sinopse = document.selectFirst("div#content > *:contains(Sinopse)")?.nextElementSibling()
            description = (sinopse?.text() ?: "Sem sinopse.") + infosText?.let { "\n\n$it" }.orEmpty()
            genre = document.select("a[rel=tag]").joinToString(", ") { it.text() }
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException("Not used.")
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException("Not used.")
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException("Not used.")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used.")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used.")

    private fun titleCase(str: String): String {
        val builder = StringBuilder(str)
        var whitespace = true
        str.forEachIndexed { index, c ->
            if (c.isWhitespace()) {
                whitespace = true
            } else if (whitespace) {
                builder.setCharAt(index, c.uppercaseChar())
                whitespace = false
            }
        }
        return builder.toString()
    }
}

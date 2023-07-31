package eu.kanade.tachiyomi.animeextension.id.nimegami

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class NimeGami : ParsedAnimeHttpSource() {

    override val name = "NimeGami"

    override val baseUrl = "https://nimegami.id"

    override val lang = "id"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.wrapper-2-a > article > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("data-lazy-src")
        title = element.selectFirst("div.title-post2")!!.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun latestUpdatesSelector() = "div.post article"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("h2 > a")!!.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst("img")!!.attr("srcset").substringBefore(" ")
    }

    override fun latestUpdatesNextPageSelector() = "ul.pagination > li > a:contains(Next)"

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
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    // TODO: Add support for search filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/page/$page/?s=$query&post_type=post")

    override fun searchAnimeSelector() = "div.archive > div > article"

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("div.coverthumbnail img")!!.attr("src")
        val infosDiv = document.selectFirst("div.info2 > table > tbody")!!
        title = infosDiv.getInfo("Judul:")
            ?: document.selectFirst("h2[itemprop=name]")!!.text()
        genre = infosDiv.getInfo("Kategori")
        artist = infosDiv.getInfo("Studio")
        status = with(document.selectFirst("h1.title")?.text().orEmpty()) {
            when {
                contains("(On-Going)") -> SAnime.ONGOING
                contains("(End)") || contains("(Movie)") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }

        description = buildString {
            document.select("div#Sinopsis p").eachText().forEach {
                append("$it\n")
            }

            val nonNeeded = listOf("Judul:", "Kategori", "Studio")
            infosDiv.select("tr")
                .eachText()
                .filterNot(nonNeeded::contains)
                .forEach { append("\n$it") }
        }
    }

    private fun Element.getInfo(info: String) =
        selectFirst("tr:has(td.tablex:contains($info))")?.text()?.substringAfter(": ")

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.list_eps_stream > li.select-eps"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val num = element.attr("id").substringAfterLast("_")
        episode_number = num.toFloatOrNull() ?: 1F
        name = "Episode $num"
        url = element.attr("data")
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

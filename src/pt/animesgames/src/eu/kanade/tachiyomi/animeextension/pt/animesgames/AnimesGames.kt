package eu.kanade.tachiyomi.animeextension.pt.animesgames

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AnimesGames : ParsedAnimeHttpSource() {

    override val name = "Animes Games"

    override val baseUrl = "https://animesgames.cc"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "ul.top10 > li > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page")

    override fun latestUpdatesSelector() = "div.conteudo section.episodioItem > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.tituloEP")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("data-lazy-src")
    }

    override fun latestUpdatesNextPageSelector() = "ol.pagination > a:contains(>)"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    @Serializable
    data class SearchResponseDto(
        val results: List<String>,
        val page: Int,
        val total_page: Int = 1,
    )

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/lista-de-animes", headers)).execute()
            .asJsoup()
            .selectFirst("div.menu_filter_box")!!
            .attr("data-secury")
    }

    override fun getFilterList() = AnimesGamesFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimesGamesFilters.getSearchParameters(filters)
        val body = FormBody.Builder().apply {
            add("pagina", "$page")
            add("type", "lista")
            add("type_url", "anime")
            add("limit", "30")
            add("token", searchToken)
            add("search", query.ifBlank { "0" })
            val filterData = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("filter_audio", params.audio)
                addQueryParameter("filter_letter", params.letter)
                addQueryParameter("filter_order", params.orderBy)
                addQueryParameter("filter_sort", "abc")
            }.build().encodedQuery.orEmpty()

            val genres = params.genres.joinToString { "\"$it\"" }
            val delgenres = params.deleted_genres.joinToString { "\"$it\"" }

            add("filters", """{"filter_data": "$filterData", "filter_genre_add": [$genres], "filter_genre_del": [$delgenres]}""")
        }.build()

        return POST("$baseUrl/func/listanime", body = body, headers = headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return runCatching {
            val data = response.parseAs<SearchResponseDto>()
            val animes = data.results.map(Jsoup::parse)
                .mapNotNull { it.selectFirst(searchAnimeSelector()) }
                .map(::searchAnimeFromElement)
            val hasNext = data.total_page > data.page
            AnimesPage(animes, hasNext)
        }.getOrElse { AnimesPage(emptyList(), false) }
    }

    override fun searchAnimeSelector() = "section.animeItem > a"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.tituloAnime")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())
        val content = doc.selectFirst("section.conteudoPost")!!
        title = content.selectFirst("section > h1")!!.text()
            .removePrefix("Assistir ")
            .removeSuffix("Temporada Online")
        thumbnail_url = content.selectFirst("img")?.attr("data-lazy-src")
        description = content.select("section.sinopseEp p").eachText().joinToString("\n")

        val infos = content.selectFirst("div.info > ol")!!

        author = infos.getInfo("Autor") ?: infos.getInfo("Diretor")
        artist = infos.getInfo("Estúdio")
        status = when (infos.getInfo("Status")) {
            "Completo" -> SAnime.COMPLETED
            "Lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(info: String) =
        selectFirst("li:has(span:contains($info))")?.run {
            selectFirst("span[data]")?.text() ?: ownText()
        }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return getRealDoc(response.asJsoup())
            .select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeListSelector() = "div.listaEp > section.episodioItem > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.selectFirst("div.tituloEP")!!.text().also {
            name = it
            episode_number = it.substringAfterLast(" ").toFloatOrNull() ?: 1F
        }
        date_upload = element.selectFirst("span.data")?.text()?.toDate() ?: 0L
    }

    // ============================ Video Links =============================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val url = doc.selectFirst("div.Link > a")
            ?.attr("href")
            ?: return emptyList()

        val playerDoc = client.newCall(GET(url, headers)).execute()
            .asJsoup()

        val iframe = playerDoc.selectFirst("iframe")
        return when {
            iframe != null -> {
                bloggerExtractor.videosFromUrl(iframe.attr("src"), headers)
            }

            else -> parseDefaultVideo(playerDoc)
        }
    }

    private fun parseDefaultVideo(doc: Document): List<Video> {
        val scriptData = doc.selectFirst("script:containsData(jw = {)")
            ?.data()
            ?: return emptyList()

        val playlistUrl = scriptData.substringAfter("file\":\"")
            .substringBefore('"')
            .replace("\\", "")

        return when {
            playlistUrl.endsWith("m3u8") -> {
                val separator = "#EXT-X-STREAM-INF:"
                client.newCall(GET(playlistUrl, headers)).execute()
                    .body.string()
                    .substringAfter(separator)
                    .split(separator)
                    .map {
                        val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                        val videoUrl = it.substringAfter("\n").substringBefore("\n")
                        Video(videoUrl, quality, videoUrl)
                    }
            }

            else -> listOf(Video(playlistUrl, "Default", playlistUrl, headers))
        }
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================= Utilities ==============================

    private fun getRealDoc(document: Document): Document {
        if (!document.location().contains("/video/")) return document

        return document.selectFirst("div.linksEP > a:has(li.episodio)")?.let {
            client.newCall(GET(it.attr("href"), headers)).execute()
                .asJsoup()
        } ?: document
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
        }
    }
}

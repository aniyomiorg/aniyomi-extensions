package eu.kanade.tachiyomi.animeextension.pt.sukianimes

import eu.kanade.tachiyomi.animeextension.pt.sukianimes.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.sukianimes.dto.SearchResultDto
import eu.kanade.tachiyomi.animeextension.pt.sukianimes.extractors.BloggerExtractor
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.api.get
import kotlin.Exception

class SukiAnimes : ParsedAnimeHttpSource() {

    override val name = "SukiAnimes"

    override val baseUrl = "https://sukianimes.com"
    private val API_URL = "$baseUrl/wp-admin/admin-ajax.php"
    private val NONCE_URL = "$baseUrl/?js_global=1&ver=6.1.1"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // ============================== Popular ===============================
    // This source doesn't have a popular anime page, so we'll grab
    // the latest anime additions instead.
    override fun popularAnimeSelector() = "section.animeslancamentos div.aniItem > a"
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)
    override fun popularAnimeNextPageSelector() = null // disable it
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img").attr("src")
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.ultEpsContainerItem > a"
    private fun episodeListNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return client.newCall(episodeListRequest(anime))
            .asObservableSuccess()
            .map { response ->
                val realDoc = getRealDoc(response.asJsoup())
                episodeListParse(realDoc).reversed()
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return episodeListParse(response.asJsoup())
    }

    private fun episodeListParse(doc: Document): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val eps = doc.select(episodeListSelector()).map(::episodeFromElement)
        episodeList.addAll(eps)
        val nextPageElement = doc.selectFirst(episodeListNextPageSelector())
        if (nextPageElement != null) {
            val nextUrl = nextPageElement.attr("href")
            val res = client.newCall(GET(nextUrl)).execute()
            episodeList.addAll(episodeListParse(res))
        }
        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val title = element.attr("title")
            name = title
            episode_number = runCatching {
                title.trim().substringAfterLast(" ").toFloat()
            }.getOrDefault(0F)
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        val players = doc.select("li.abasPlayers").map {
            val url = it.attr("data-playerurl")
            Pair(it.text(), url)
        }.ifEmpty {
            val defaultPlayer = doc.selectFirst("div.playerBoxInfra > iframe")
                ?: doc.selectFirst("video#player")
            listOf(Pair("Default", defaultPlayer.attr("src")))
        }

        val videos = players.mapNotNull { (name, url) ->
            when {
                url.contains("$baseUrl/player") ->
                    BloggerExtractor(client).videoFromUrl(url, name, headers)

                // Unfortunately, most of the links are broken.
                url.contains("fy.v.vrv.co") -> null

                else -> listOf(Video(url, name, url, headers = headers))
            }
        }.flatten()
        return videos
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    // We'll be using serialization in the search system,
    // so those functions won't be used.
    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")
    override fun searchAnimeSelector() = throw Exception("not used")
    override fun searchAnimeNextPageSelector() = throw Exception("not used")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = SKFilters.filterList

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$slug", headers))
                .asObservableSuccess()
                .map { searchAnimeBySlugParse(it, slug) }
        } else {
            val params = SKFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { searchAnimeParse(it, page) }
        }
    }

    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/anime/$slug"
        return AnimesPage(listOf(details), false)
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: SKFilters.FilterSearchParams): Request {
        val body = FormBody.Builder().apply {
            val nonceReq = client.newCall(GET(NONCE_URL)).execute()
            val nonce = nonceReq.body?.string()
                .orEmpty()
                .substringAfter("'")
                .substringBefore("'")

            add("action", "show_animes_ajax")
            if (filters.adult)
                add("adulto", "yes")
            else
                add("adulto", "no")
            add("formato", filters.format)
            add("nome", query)
            add("paged", "$page")
            add("search_nonce", nonce)
            add("status", filters.status)
            add("tipo", filters.type)
            filters.genres.forEach { add("generos[]", it) }
        }.build()

        return POST(API_URL, body = body)
    }

    private fun searchAnimeParse(response: Response, page: Int): AnimesPage {
        val searchData = runCatching {
            response.parseAs<SearchResultDto>()
        }.getOrDefault(SearchResultDto())
        val animes = searchData.animes.map(::searchAnimeParseFromObject)
        val hasNextPage = searchData.pages > 0 && searchData.pages != page
        return AnimesPage(animes, hasNextPage)
    }

    private fun searchAnimeParseFromObject(anime: AnimeDto): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(anime.permalink)
            title = anime.title
            thumbnail_url = anime.thumbnail_url
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            thumbnail_url = doc.selectFirst("div.animeimgleft > img").attr("src")
            val section = doc.selectFirst("section.rightnew")
            val titleSection = section.selectFirst("section.anime_titulo")
            title = titleSection.selectFirst("h1").text()
            status = parseStatus(titleSection.selectFirst("div.anime_status"))
            genre = titleSection.select("div.anime_generos > span")
                .joinToString(", ") { it.text() }

            var desc = doc.selectFirst("span#sinopse_content").text()
            desc += "\n\n" + section.select("div.anime_info").joinToString("\n") {
                val key = it.selectFirst("span.anime_info_title").text()
                val value = it.selectFirst("span.anime_info_content").text()
                "$key: $value"
            }
            description = desc
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.paginacao > a.next"
    override fun latestUpdatesSelector() = "div.epiItem > div.epiImg > a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img").attr("src")
        }
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/lista-de-episodios/page/$page/")

    // ============================= Utilities ==============================

    private fun getRealDoc(doc: Document): Document {
        val controls = doc.selectFirst("div.episodioControles")
        if (controls != null) {
            val newUrl = controls.select("a").get(1)!!.attr("href")
            val res = client.newCall(GET(newUrl)).execute()
            return res.asJsoup()
        } else {
            return doc
        }
    }

    private fun parseStatus(element: Element?): Int {
        return when (element?.text()?.trim()) {
            "Em Lançamento" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"
    }
}

package eu.kanade.tachiyomi.animeextension.pt.anidong

import eu.kanade.tachiyomi.animeextension.pt.anidong.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.anidong.dto.EpisodeListDto
import eu.kanade.tachiyomi.animeextension.pt.anidong.dto.SearchResultDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class AniDong : ParsedAnimeHttpSource() {

    override val name = "AniDong"

    override val baseUrl = "https://anidong.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiHeaders by lazy {
        headersBuilder() // sets user-agent
            .add("Referer", baseUrl)
            .add("x-requested-with", "XMLHttpRequest")
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "article.top10_animes_item > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page/")

    override fun latestUpdatesSelector() = "article.main_content_article > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "div.paginacao > a.next"

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
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun getFilterList() = AniDongFilters.FILTER_LIST

    private val nonce by lazy {
        client.newCall(GET("$baseUrl/?js_global=1&ver=6.2.2")).execute()
            .body.string()
            .substringAfter("search_nonce")
            .substringAfter("'")
            .substringBefore("'")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniDongFilters.getSearchParameters(filters)

        val body = FormBody.Builder()
            .add("letra", "")
            .add("action", "show_animes_ajax")
            .add("nome", query)
            .add("status", params.status)
            .add("formato", params.format)
            .add("search_nonce", nonce)
            .add("paged", page.toString())
            .apply {
                params.genres.forEach { add("generos[]", it) }
            }.build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", headers = apiHeaders, body = body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchData: SearchResultDto = response.body.string()
            .takeIf { it.trim() != "402" }
            ?.let(json::decodeFromString)
            ?: return AnimesPage(emptyList(), false)

        val animes = searchData.animes.map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.url)
                title = it.title
                thumbnail_url = it.thumbnail_url
            }
        }

        val hasNextPage = searchData.pages > 1 && searchData.animes.size == 10

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        val infos = doc.selectFirst("div.anime_infos")!!

        setUrlWithoutDomain(doc.location())
        title = infos.selectFirst("div > h3")!!.ownText()
        thumbnail_url = infos.selectFirst("img")?.attr("src")
        genre = infos.select("div[itemprop=genre] a").eachText().joinToString()
        artist = infos.selectFirst("div[itemprop=productionCompany]")?.text()

        status = doc.selectFirst("div:contains(Status) span")?.text().let {
            when {
                it == null -> SAnime.UNKNOWN
                it == "Completo" -> SAnime.COMPLETED
                it.contains("Lançamento") -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }

        description = buildString {
            infos.selectFirst("div.anime_name + div.anime_info")?.text()?.also {
                append("Nomes alternativos: $it\n")
            }

            doc.selectFirst("div[itemprop=description]")?.text()?.also {
                append("\n$it")
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.asJsoup())

        val id = doc.selectFirst("link[rel=shortlink]")!!.attr("href").substringAfter("=")
        val body = FormBody.Builder()
            .add("action", "show_videos")
            .add("anime_id", id)
            .build()

        val res = client.newCall(POST("$baseUrl/api", apiHeaders, body)).execute()
            .body.string()
        val data = json.decodeFromString<EpisodeListDto>(res)

        return buildList {
            data.episodes.forEach { add(episodeFromObject(it, "Episódio")) }
            data.movies.forEach { add(episodeFromObject(it, "Filme")) }
            data.ovas.forEach { add(episodeFromObject(it, "OVA")) }
            sortByDescending { it.episode_number }
        }
    }

    private fun episodeFromObject(episode: EpisodeDto, prefix: String) = SEpisode.create().apply {
        setUrlWithoutDomain(episode.epi_url)
        episode_number = episode.epi_num.toFloatOrNull() ?: 0F
        name = "$prefix ${episode.epi_num}"
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        return doc.select("div.player_option").flatMap {
            val url = it.attr("data-playerlink")
            val playerName = it.text().trim()
            videosFromUrl(url, playerName)
        }
    }

    private fun videosFromUrl(url: String, playerName: String): List<Video> {
        val scriptData = client.newCall(GET(url, apiHeaders)).execute()
            .asJsoup()
            .selectFirst("script:containsData(sources)")
            ?.data() ?: return emptyList()

        return scriptData.substringAfter("sources: [").substringBefore("]")
            .split("{")
            .drop(1)
            .map {
                val videoUrl = it.substringAfter("file: \"").substringBefore('"')
                val label = it.substringAfter("label: \"", "Unknown").substringBefore('"')
                val quality = "$playerName - $label"
                Video(videoUrl, quality, videoUrl, headers = apiHeaders)
            }
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        if (!document.location().contains("/video/")) return document

        return document.selectFirst(".episodioControleItem:has(i.ri-grid-fill)")?.let {
            client.newCall(GET(it.attr("href"), headers)).execute()
                .asJsoup()
        } ?: document
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

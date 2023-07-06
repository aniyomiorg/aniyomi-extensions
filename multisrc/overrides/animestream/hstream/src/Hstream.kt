package eu.kanade.tachiyomi.animeextension.en.hstream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Hstream : AnimeStream(
    "en",
    "Hstream",
    "https://hstream.moe",
) {
    override val animeListUrl = "$baseUrl/hentai"

    override val dateFormatter by lazy {
        SimpleDateFormat("yyyy-mm-dd", Locale.ENGLISH)
    }

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/list")

    override fun popularAnimeSelector() = "div.soralist ul > li > a.tip"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val href = element.attr("href")
        setUrlWithoutDomain(href)
        title = element.ownText()
        thumbnail_url = "$baseUrl/images$href/cover.webp"
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/search?page=$page&order=latest")
    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = HstreamFilters.getSearchParameters(filters)
        val multiString = buildString {
            if (params.tags.isNotEmpty()) append(params.tags + "&")
            if (params.studios.isNotEmpty()) append(params.studios + "&")
            if (query.isNotEmpty()) append("s=$query")
        }

        return GET("$animeListUrl/search?page=$page&order=${params.order}&$multiString", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val original = super.searchAnimeParse(response)
        val animes = original.animes.distinctBy { it.url }
        return AnimesPage(animes, original.hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        val href = element.attr("href").substringBeforeLast("/")
        setUrlWithoutDomain(href)
        title = element.selectFirst("div.tt, div.ttl")!!
            .ownText()
            .substringBeforeLast(" -")
        thumbnail_url = "$baseUrl/images$href/cover.webp"
    }

    // ============================== Filters ===============================
    override val fetchFilters = false

    override fun getFilterList() = HstreamFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun getAnimeDescription(document: Document) =
        super.getAnimeDescription(document)
            ?.substringAfter("720p 1080p and (if available) 2160p (4k).")
            ?.trim()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    // ============================ Video Links =============================
    private val urlRegex by lazy { Regex("https?:\\/\\/[^\\s][^']+") }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(const player)")!!.data()
        val links = urlRegex.findAll(script).map { it.value }.toList()

        val subTracks = links.filter { it.endsWith(".ass") }.map {
            val name = it.substringAfterLast("/").substringBefore(".").uppercase()
            Track(it, name)
        }.distinctBy { it.lang }

        return links.filter { it.endsWith(".webm") || it.endsWith(".mp4") }.map {
            val quality = it.split(".").takeLast(2).firstOrNull() ?: "720p"
            Video(it, quality, it, headers, subtitleTracks = subTracks)
        }
    }

    override val prefQualityValues = arrayOf("2160p", "1080p", "720p")
    override val prefQualityEntries = prefQualityValues
}

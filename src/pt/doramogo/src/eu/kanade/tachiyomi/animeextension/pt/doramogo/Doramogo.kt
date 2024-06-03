package eu.kanade.tachiyomi.animeextension.pt.doramogo

import eu.kanade.tachiyomi.animeextension.pt.doramogo.extractors.DoramogoExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Doramogo : ParsedAnimeHttpSource() {

    override val name = "Doramogo"

    override val baseUrl = "https://doramogo.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/doramas/?filter_order=popular", headers)

    override fun popularAnimeSelector() = "div.item-drm"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("div.title h3")!!.text()
        thumbnail_url = element.selectFirst("div.cover > img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/doramas/?filter_orderby=date", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/dorama/$id"))
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

    override fun getFilterList() = DoramogoFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DoramogoFilters.getSearchParameters(filters)

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addPathSegment(query)
            .addIfNotBlank("filter_audio", params.audio)
            .addIfNotBlank("filter_genre", params.genre)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("div.dados h1")!!.text()
        thumbnail_url = document.select("div.image--cover").attr("style")
            .substringAfter("background-image: url('").substringBefore("')")
        description = document.selectFirst("p.readMor")!!.textNodes().joinToString("")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "li.episode--content"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        element.selectFirst("div.title-episode a")!!.textNodes().joinToString("").let {
            name = it.substringAfter(". ")
            episode_number = it.substringBefore(". ").toFloatOrNull() ?: 1F
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val urls = document.select("div.source-box iframe[src]").map {
            it.attr("src")
        }

        return urls.parallelCatchingFlatMapBlocking { getVideosFromURL(it) }
    }

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val doramogoExtractor by lazy { DoramogoExtractor(client, headers) }
    private val gdriveExtractor by lazy { GoogleDriveExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private fun getVideosFromURL(url: String): List<Video> {
        return when {
            "dailymotion" in url -> dailymotionExtractor.videosFromUrl(url)
            "ok.ru" in url -> okruExtractor.videosFromUrl(url)

            "drive.google.com" in url -> {
                val id = Regex("[\\w-]{28,}").find(url)?.groupValues?.get(0) ?: return emptyList()
                gdriveExtractor.videosFromUrl(id, "GDrive")
            }

            "embedrise.com" in url -> {
                val m3u8Url = client.newCall(GET(url)).execute()
                    .asJsoup()
                    .selectFirst("video source")?.attr("src") ?: return emptyList()
                playlistUtils.extractFromHls(
                    m3u8Url,
                    referer = url,
                    videoNameGen = { "Embedrise - $it" },
                )
            }

            "streamable.com" in url -> {
                val mp4Url = client.newCall(GET(url)).execute()
                    .asJsoup()
                    .selectFirst("video")
                    ?.attr("src")
                    ?.let {
                        if (it.startsWith("//")) {
                            return@let "https:$it"
                        }
                        it
                    }
                    ?: return emptyList()
                listOf(
                    Video(mp4Url, "Streamable", mp4Url, headers),
                )
            }

            "/player/" in url -> doramogoExtractor.videosFromUrl(url)

            else -> emptyList()
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

    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

package eu.kanade.tachiyomi.animeextension.en.hstream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

class Hstream : ParsedAnimeHttpSource() {

    override val name = "Hstream"

    override val baseUrl = "https://hstream.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/search?order=view-count&page=$page")

    override fun popularAnimeSelector() = "div.items-center div.w-full > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("img")!!.attr("alt")
        val episode = url.substringAfterLast("-").substringBefore("/")
        thumbnail_url = "$baseUrl/images${url.substringBeforeLast("-")}/cover-ep-$episode.webp"
    }

    override fun popularAnimeNextPageSelector() = "span[aria-current] + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/search?order=recently-uploaded&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/hentai/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    // TODO: Implement search filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/search?s=$query&order=view-count&page=$page")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        status = SAnime.COMPLETED

        val floatleft = document.selectFirst("div.relative > div.float-left > div")!!
        title = floatleft.selectFirst("h1")!!.text()
        artist = floatleft.selectFirst("h2 > a")?.text()

        thumbnail_url = document.selectFirst("div.float-left > img.object-cover")?.absUrl("src")
        genre = document.select("ul.list-none > li > a").eachText().joinToString()

        description = document.selectFirst("div.relative > p.leading-tight")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { it.asJsoup() }
        val episode = SEpisode.create().apply {
            date_upload = doc.selectFirst("a:has(i.fa-upload)")?.ownText().toDate()
            setUrlWithoutDomain(doc.location())
            val num = url.substringAfterLast("-").substringBefore("/")
            episode_number = num.toFloatOrNull() ?: 1F
            name = "Episode $num"
        }

        return listOf(episode)
    }

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }

        val token = client.cookieJar.loadForRequest(response.request.url)
            .first { it.name.equals("XSRF-TOKEN") }
            .value

        val episodeId = doc.selectFirst("input#e_id")!!.attr("value")

        val newHeaders = headersBuilder().apply {
            set("Referer", doc.location())
            set("Origin", baseUrl)
            set("X-Requested-With", "XMLHttpRequest")
            set("X-XSRF-TOKEN", URLDecoder.decode(token, "utf-8"))
        }.build()

        val body = """{"episode_id": "$episodeId"}""".toRequestBody("application/json".toMediaType())
        val data = client.newCall(POST("$baseUrl/player/api", newHeaders, body)).execute()
            .parseAs<PlayerApiResponse>()

        val urlBase = data.stream_domains.random() + "/" + data.stream_url
        val subtitleList = listOf(Track(urlBase, "English"))

        val resolutions = listOfNotNull("720", "1080", if (data.resolution == "4k") "2160" else null)
        return resolutions.map { resolution ->
            val url = urlBase + getVideoUrlPath(data.legacy != 0, resolution)
            Video(url, "${resolution}p", url, subtitleTracks = subtitleList)
        }
    }

    private fun getVideoUrlPath(isLegacy: Boolean, resolution: String): String {
        return if (isLegacy) {
            if (resolution.equals("720")) {
                "/x264.720p.mp4"
            } else {
                "/av1.$resolution.webm"
            }
        } else {
            "/$resolution/manifest.mpd"
        }
    }

    @Serializable
    data class PlayerApiResponse(
        val legacy: Int = 0,
        val resolution: String = "4k",
        val stream_url: String,
        val stream_domains: List<String>,
    )

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
    private fun String?.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(orEmpty().trim(' ', '|'))?.time }
            .getOrNull() ?: 0L
    }

    private inline fun <reified T> Response.parseAs(): T {
        return use { it.body.string() }.let(json::decodeFromString)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "id:"
    }
}

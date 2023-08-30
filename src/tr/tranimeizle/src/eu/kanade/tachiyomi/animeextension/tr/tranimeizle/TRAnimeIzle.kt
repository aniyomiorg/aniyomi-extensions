package eu.kanade.tachiyomi.animeextension.tr.tranimeizle

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class TRAnimeIzle : ParsedAnimeHttpSource() {

    override val name = "TR Anime Izle"

    override val baseUrl = "https://www.tranimeizle.co"

    override val lang = "tr"

    override val supportsLatest = true

    override val client by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(ShittyCaptchaInterceptor(baseUrl, headers))
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/listeler/populer/sayfa-$page")

    override fun popularAnimeSelector() = "div.post-body div.flx-block"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("data-href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        title = element.selectFirst("div.bar > h4")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li:has(.ti-angle-right):not(.disabled)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/listeler/yenibolum/sayfa-$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) =
        popularAnimeFromElement(element).apply {
            // Convert episode url to anime url
            url = "/anime$url".substringBefore("-bolum").substringBeforeLast("-") + "-izle"
        }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/arama/$query?page=$page")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("div.playlist-title h1")!!.text()
        thumbnail_url = document.selectFirst("div.poster .social-icon img")!!.attr("src")

        val infosDiv = document.selectFirst("div.col-md-6 > div.row")!!
        genre = infosDiv.select("div > a.genre").eachText().joinToString()
        author = infosDiv.select("dd:contains(Fansublar) + dt a").eachText().joinToString()

        description = buildString {
            document.selectFirst("div.p-10 > p")?.text()?.also(::append)

            var dtCount = 0 // AAAAAAAA I HATE MUTABLE VALUES
            infosDiv.select("dd, dt").forEach {
                // Ignore non-wanted info
                it.selectFirst("dd:contains(Puanlama), dd:contains(Anime Türü), dt:has(i.fa-star), dt:has(a.genre)")
                    ?.let { return@forEach }

                val text = it.text()
                // yes
                when (it.tagName()) {
                    "dd" -> {
                        append("\n$text: ")
                        dtCount = 0
                    }
                    "dt" -> {
                        if (dtCount == 0) {
                            append(text)
                        } else {
                            append(", $text")
                        }
                        dtCount++
                    }
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.animeDetail-items > ol a:has(div.episode-li)"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epNum = element.selectFirst(".etitle > span")!!.text()
            .substringBefore(". Bölüm", "")
            .substringAfterLast(" ", "")
            .toIntOrNull() ?: 1 // Int because of the episode name, a Float would render with more zeros.

        name = "Episode $epNum"
        episode_number = epNum.toFloat()

        date_upload = element.selectFirst(".etitle > small.author")?.text()?.toDate() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }
        val episodeId = doc.selectFirst("input#EpisodeId")!!.attr("value")
        return doc.select("div.fansubSelector").flatMap { fansub ->
            val fansubId = fansub.attr("data-fid")
            val body = """{"EpisodeId":$episodeId,"FansubId":$fansubId}"""
                .toRequestBody("application/json".toMediaType())

            client.newCall(POST("$baseUrl/api/fansubSources", headers, body)).execute()
                .use { it.asJsoup() }
                .select("li.sourceBtn")
                .parallelMap {
                    runCatching {
                        getVideosFromId(it.attr("data-id"))
                    }.getOrElse { emptyList() }
                }.flatten()
        }
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    private fun getVideosFromId(id: String): List<Video> {
        val url = client.newCall(POST("$baseUrl/api/sourcePlayer/$id")).execute()
            .use { it.body.string() }
            .substringAfter("src=")
            .substringAfter('"')
            .substringAfter("/embed2/?id=")
            .substringBefore('"')
            .replace("\\", "")
            .trim()

        // That's going to take an entire year to load, and I really don't care.
        return when {
            "filemoon.sx" in url -> filemoonExtractor.videosFromUrl(url, headers = headers)
            "mixdrop" in url -> mixDropExtractor.videoFromUrl(url)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers)
            "ok.ru" in url -> okruExtractor.videosFromUrl(url)
            "sendvid.com" in url -> sendvidExtractor.videosFromUrl(url)
            "video.sibnet" in url -> sibnetExtractor.videosFromUrl(url)
            "streamlare.com" in url -> streamlareExtractor.videosFromUrl(url)
            "voe.sx" in url -> voeExtractor.videoFromUrl(url)?.let(::listOf) ?: emptyList()
            "yourupload.com" in url -> yourUploadExtractor.videoFromUrl(url, headers)
            else -> emptyList()
        }
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

    // ============================= Utilities ==============================
    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd MMM yyyy", Locale("tr"))
        }
    }
}

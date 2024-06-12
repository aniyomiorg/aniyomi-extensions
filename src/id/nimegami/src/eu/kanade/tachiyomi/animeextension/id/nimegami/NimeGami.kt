package eu.kanade.tachiyomi.animeextension.id.nimegami

import android.util.Base64
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator as Synchrony

class NimeGami : ParsedAnimeHttpSource() {

    override val name = "NimeGami"

    override val baseUrl = "https://nimegami.id"

    override val lang = "id"

    override val supportsLatest = true

    private val json: Json by injectLazy()

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
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
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
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val qualities = json.decodeFromString<List<VideoQuality>>(episode.url.b64Decode())
        val episodeIndex = episode.episode_number.toInt() - 1
        var usedBunga = false // to prevent repeating the same request to bunga.nimegami
        return qualities.flatMap {
            val quality = it.format
            it.url.mapNotNull { url ->
                if (url.contains("bunga.nimegami")) {
                    if (usedBunga) {
                        return@mapNotNull null
                    } else {
                        usedBunga = true
                    }
                }
                runCatching {
                    extractVideos(url, quality, episodeIndex)
                }.getOrElse { emptyList() }
            }.flatten()
        }.let { it }
    }

    private fun extractVideos(url: String, quality: String, episodeIndex: Int): List<Video> {
        return with(url) {
            when {
                contains("video.nimegami.id") -> {
                    val realUrl = url.substringAfter("url=").substringBefore("&").b64Decode()
                    extractVideos(realUrl, quality, episodeIndex)
                }

                contains("berkasdrive") || contains("drive.nimegami") -> {
                    client.newCall(GET(url, headers)).execute()
                        .asJsoup()
                        .selectFirst("source[src]")
                        ?.attr("src")
                        ?.let {
                            listOf(Video(it, "Berkasdrive - $quality", it, headers))
                        } ?: emptyList()
                }

                contains("hxfile.co") -> {
                    val embedUrl = when {
                        "embed-" in url -> url
                        else -> url.replace(".co/", ".co/embed-") + ".html"
                    }

                    client.newCall(GET(embedUrl, headers)).execute()
                        .asJsoup()
                        .selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")
                        ?.data()
                        ?.let(JsUnpacker::unpackAndCombine)
                        ?.substringAfter("sources:[", "")
                        ?.substringAfter("file\":\"", "")
                        ?.substringBefore('"')
                        ?.takeIf(String::isNotBlank)
                        ?.let { listOf(Video(it, "HXFile - $quality", it, headers)) }
                        ?: emptyList()
                }

                contains("bunga.nimegami") -> {
                    val episodeUrl = url.replace("select_eps", "eps=$episodeIndex")
                    client.newCall(GET(episodeUrl, headers)).execute()
                        .asJsoup()
                        .select("div.server_list ul > li")
                        .map { it.attr("url") to it.text() }
                        .filter { it.first.contains("uservideo") } // naniplay is absurdly slow
                        .parallelFlatMapBlocking(::extractUserVideo)
                }

                else -> emptyList()
            }
        }
    }

    private val urlPartRegex by lazy {
        Regex("\\.(?:title|file) =(?:\n.*?'| ')(.*?)'", RegexOption.MULTILINE)
    }

    private suspend fun extractUserVideo(pair: Pair<String, String>): List<Video> {
        val (url, quality) = pair
        val doc = client.newCall(GET(url, headers)).await().asJsoup()
        val scriptUrl = doc.selectFirst("script[src*=/s/?data]")?.attr("src")
            ?: return emptyList()

        return client.newCall(GET(scriptUrl, headers)).await()
            .body.string()
            .let(Synchrony::deobfuscateScript)
            ?.let(urlPartRegex::findAll)
            ?.map { it.groupValues.drop(1) }
            ?.flatten()
            ?.chunked(2)
            ?.mapNotNull { videoPair ->
                runCatching {
                    val (part, videoUrl) = videoPair
                    Video(videoUrl, "$quality - $part", videoUrl, headers)
                }.getOrNull()
            }
            ?.toList()
            ?: emptyList()
    }

    @Serializable
    data class VideoQuality(val format: String, val url: List<String>)

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException()
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
    private fun String.b64Decode() = String(Base64.decode(this, Base64.DEFAULT))

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

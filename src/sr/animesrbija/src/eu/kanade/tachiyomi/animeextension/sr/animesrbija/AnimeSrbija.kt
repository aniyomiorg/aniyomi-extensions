package eu.kanade.tachiyomi.animeextension.sr.animesrbija

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeSrbija : ParsedAnimeHttpSource() {

    override val name = "Anime Srbija"

    override val baseUrl = "https://www.animesrbija.com"

    override val lang = "sr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Popular Anime
    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").last().attr("href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("title")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String? {
        return ".next"
    }

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/anime-lista/page/$page/?order=popular")
    }

    override fun popularAnimeSelector(): String {
        return ".film-list > .item"
    }

    // Latest anime
    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").last().attr("href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("title")

        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? {
        return ".next"
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/anime-lista/page/$page/?order=update")
    }

    override fun latestUpdatesSelector(): String {
        return ".film-list > .item"
    }

    // Search anime
    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").last().attr("href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("title")

        return anime
    }

    override fun searchAnimeNextPageSelector(): String? {
        return ".next"
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query")
    }

    override fun searchAnimeSelector(): String {
        return ".film-list > .item"
    }

    // Episode
    override fun episodeListSelector(): String {
        return ".ep-item a"
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.text()
        val episodeNumberString = element.text().removePrefix("Episode ")
        episode.episode_number = if (episodeNumberString.toFloatOrNull() != null) episodeNumberString.toFloat() else 0.0f

        return episode
    }

    // Video
    override fun videoFromElement(element: Element): Video {
        val source = element.attr("src").substringAfter("?file=")
        val relative = source.substringAfter("/file/")
        val testSource = "https://cdn.asroll.tk/file/$relative"
        return Video(testSource, "AS Cloud", testSource)
    }

    override fun videoListSelector(): String {
        return "iframe"
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeId = document.select("div.prevnext:nth-child(4)").first().attr("data-post-id")
        val nume: String = "1"

        val referer = response.request.url.encodedPath
        val newHeaderList = mutableMapOf(Pair("referer", baseUrl + referer))
        headers.forEach { newHeaderList[it.first] = it.second }
        val bodyString = "action=player_ajax&post=$episodeId&nume=$nume"
        val body = bodyString.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val iframe = client.newCall(POST("https://www.animesrbija.com/wp-admin/admin-ajax.php", newHeaderList.toHeaders(), body)).execute().asJsoup()
        return iframe.select(videoListSelector()).map { videoFromElement(it) }
    }

    // Anime
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select(".dc-title").text()
        anime.genre = document.select(".dcis.dcis-01 a").joinToString(" ") { it.text() }
        anime.description = document.select(".dci-desc p").text()
        val status = document.select("div.dcis:nth-child(4)").text().substringAfter("Status: ")
        anime.status = when {
            (status.equals(" Currently Airing")) -> SAnime.ONGOING
            (status.equals(" Finished Airing")) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        return anime
    }
}

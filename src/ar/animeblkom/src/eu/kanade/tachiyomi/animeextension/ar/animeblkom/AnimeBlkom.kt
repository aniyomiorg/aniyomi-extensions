package eu.kanade.tachiyomi.animeextension.ar.animeblkom

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class AnimeBlkom : ParsedAnimeHttpSource() {

    override val name = "أنمي بالكوم"

    override val baseUrl = "https://animeblkom.net"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://cdn2.vid4up.xyz")
    }

    override fun popularAnimeSelector(): String = "div.contents div.content div.content-inner div.poster a" // "ul.anime-loop.loop li a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-list?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + element.select("img").first().attr("data-original")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("img").attr("alt").removePrefix(" poster")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun episodeListSelector() = "ul.episodes-links li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        // val episodeNumberString = element.select("span:nth-child(3)")
        episode.episode_number = element.select("span:nth-child(3)").text().toFloat()
        episode.name = element.select("span:nth-child(3)").text() + " :" + element.select("span:nth-child(1)").text()

        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("src")
        val referer = response.request.url.encodedPath
        val newHeaders = Headers.headersOf("referer", baseUrl + referer)
        val iframeResponse = client.newCall(GET(iframe, newHeaders))
            .execute().asJsoup()
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        Log.i("lol", element.attr("src"))
        return Video(element.attr("src").replace("watch", "download"), element.attr("res") + "p", element.attr("src").replace("watch", "download"), null)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("img").attr("alt").removePrefix(" poster")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "div.contents div.content div.content-inner div.poster a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?query=$query&page=$page")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.select("div.poster img").first().attr("data-original")
        anime.title = document.select("div.name span h1").text()
        anime.genre = document.select("p.genres a").joinToString(", ") { it.text() }
        anime.description = document.select("div.story p").text()
        anime.author = document.select("div:contains(الاستديو) span > a").text()
        anime.artist = document.select("div:contains(المخرج) span:nth-child(2)").text()
        anime.status = parseStatus(document.select("div:contains(حالة الأنمي) span:nth-child(2)").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "مستمر" -> SAnime.ONGOING
            "مكتمل" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        anime.title = element.select("div span").not(".badge").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime?s=rel-d&page=$page")

    override fun latestUpdatesSelector(): String = "ul.anime-loop.loop li a"
}

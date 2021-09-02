package eu.kanade.tachiyomi.animeextension.ar.mycima

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class MyCima : ParsedAnimeHttpSource() {

    override val name = "MY Cima"

    override val baseUrl = "https://mycima.dev:2053"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularAnimeSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/seriestv/top/?page_number=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a").attr("title")
        anime.thumbnail_url = element.select("a > span.BG--GridItem").attr("data-lazy-style")
            .substringAfter("background-image:url(").substringBefore(");")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun episodeListSelector() = "div.Episodes--Seasons--Episodes a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.select("episodetitle").text()
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("data-lazy-src")
        val referer = response.request.url.encodedPath
        val newHeaderList = mutableMapOf(Pair("referer", baseUrl + referer))
        headers.forEach { newHeaderList[it.first] = it.second }
        val iframeResponse = runBlocking {
            client.newCall(GET(iframe, newHeaderList.toHeaders()))
                .await().asJsoup()
        }
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "div.Download--Mycima--Single > ul:nth-child(2) > li > a, source"

    override fun videoFromElement(element: Element): Video {
        Log.i("lol", element.attr("href, src"))
        return Video(element.attr("abs:href"), element.select("resolution").text(), element.attr("abs:src"), null)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a > strong.hasyear").text()
        anime.thumbnail_url = element.select("a > span.BG--GridItem").attr("data-lazy-style").substringAfter("background-image:url(").substringBefore(");")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun searchAnimeSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/$query/page/$page/")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.Title--Content--Single-begin > h1").text()
        anime.genre = document.select("li:contains(التصنيف) > p > a, li:contains(النوع) > p > a").joinToString(", ") { it.text() }
        anime.description = document.select("div.AsideContext > div.StoryMovieContent").text()
        anime.author = document.select("li:contains(شركات الإنتاج) > p > a").joinToString(", ") { it.text() }
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.attr("title")
        anime.thumbnail_url = element.select("a > span").attr("data-lazy-style").substringAfter("background-image:url(").substringBefore(");")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/cima1/$page")

    override fun latestUpdatesSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem"
}

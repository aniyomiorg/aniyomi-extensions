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

    override fun popularAnimeSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem a" // "ul.anime-loop.loop li a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.attr("title")
        anime.thumbnail_url = element.select("span").first().attr("style").substringAfter("background-image:url(").substringBefore(");")
        // element.select("div.itemtype_anime_poster img").first().attr("abs:src")
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

    /*override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val server = jObject.get("videos_manifest").asJsonObject.get("servers").asJsonArray[0].asJsonObject
        val streams = server.get("streams").asJsonArray
        val linkList = mutableListOf<Video>()
        for (stream in streams) {
            if (stream.asJsonObject.get("kind").asString != "premium_alert") {
                linkList.add(Video(stream.asJsonObject.get("url").asString, stream.asJsonObject.get("height").asString + "p", stream.asJsonObject.get("url").asString, null))
            }
        }
        return linkList
    }*/

    override fun videoListSelector() = "div.vjs-quality-dropdown > ul > li > a, source" // "source"

    override fun videoFromElement(element: Element): Video {
        Log.i("lol", element.attr("src, href"))
        // element.attr("abs:href")
        return Video(element.attr("href"), element.select("a").text(), element.attr("src"), null)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.attr("title")
        anime.thumbnail_url = element.select("span").attr("style").substringAfter("background-image:url(").substringBefore(")")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun searchAnimeSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/$query/page/$page/")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.Poster--Single-begin a").first().attr("src")
        anime.title = document.select("span[itemprop=name]").text()
        anime.genre = document.select("li:contains(التصنيف) > p > a, li:contains(النوع) > p > a").joinToString(", ") { it.text() }
        // anime.//description = document.select("div.card-body").text()
        // anime.author = document.select("li.production span.value").joinToString(", ") { it.text() }
        // anime.status = parseStatus(document.select("li.status span.value").text())
        return anime
    }

    /*private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }*/

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div span").not(".badge").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime?s=rel-d&page=$page")

    override fun latestUpdatesSelector(): String = "ul.anime-loop.loop li a"
}

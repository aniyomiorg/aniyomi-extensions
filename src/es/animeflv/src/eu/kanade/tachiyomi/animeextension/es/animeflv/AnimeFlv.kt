package eu.kanade.tachiyomi.animeextension.es.animeflv

import com.google.gson.JsonParser
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class AnimeFlv : ParsedAnimeHttpSource() {

    override val name = "AnimeFLV"

    override val baseUrl = "https://www3.animeflv.net"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularAnimeSelector(): String = "div.Container ul.ListAnimes li article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            baseUrl + element.select("div.Description a.Button")
                .attr("href")
        )
        anime.title = element.select("a h3").text()
        anime.thumbnail_url = try {
            element.select("a div.Image figure img").attr("src")
        } catch (e: Exception) {
            element.select("a div.Image figure img").attr("data-cfsrc")
        }
        anime.description =
            element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a[rel=next]:not(li.disabled)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        jsoup.select("script").forEach { script ->
            if (script.data().contains("var episodes = [")) {
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                val animeId = response.request.url.pathSegments.last()
                data.split("],").forEach {
                    val epNum = it.removePrefix("[").substringBefore(",")
                    val episode = SEpisode.create().apply {
                        episode_number = epNum.toFloat()
                        name = "Episodio $epNum"
                        url = "/ver/$animeId-$epNum"
                        date_upload = System.currentTimeMillis()
                    }
                    episodes.add(episode)
                }
            }
        }
        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("var videos = {")) {
                val data = script.data().substringAfter("var videos = ").substringBefore(";")
                val json = JsonParser.parseString(data).asJsonObject
                val sub = json.get("SUB")
                if (!sub.isJsonNull) {
                    for (server in sub.asJsonArray) {
                        val url = server.asJsonObject.get("code").asString.replace("\\/", "/")
                        val quality = server.asJsonObject.get("title").asString
                        val video = Video(url, quality, null, null)
                        if (quality == "GoCDN") videoList.add(video)
                    }
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlRequest(video: Video): Request {
        if (video.url.contains("https://streamium.xyz/gocdn.html")) {
            val newUrl = video.url.replace("gocdn.html#", "gocdn.php?v=")
            return GET(newUrl)
        }
        return super.videoUrlRequest(video)
    }

    override fun videoUrlParse(document: Document): String {
        return document.text()
            .substringAfter("{\"file\":\"")
            .substringBeforeLast("\"}")
            .replace("\\/", "/")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/browse?q=$query&page=$page")

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + "/" + document.selectFirst("div.AnimeCover div.Image figure img").attr("src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container h1.Title").text()
        anime.description = document.selectFirst("div.Description").text().removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")
}

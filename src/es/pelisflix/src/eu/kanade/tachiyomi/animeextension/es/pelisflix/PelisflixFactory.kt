package eu.kanade.tachiyomi.animeextension.es.pelisflix

import android.util.Log
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element

class PelisflixFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(PelisflixClass(), SeriesflixClass())
}

class PelisflixClass : Pelisflix("Pelisflix", "https://pelisflix.app")

class SeriesflixClass : Pelisflix("Seriesflix", "https://seriesflix.video") {
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ver-series-online/page/$page")

    override fun popularAnimeSelector() = "li[id*=post-] > article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h2.Title").text()
        anime.thumbnail_url = element.selectFirst("a div.Image figure.Objf img").attr("src")
        anime.description = element.select("div.TPMvCn div.Description p:nth-child(1)").text().removeSurrounding("\"")
        return anime
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("ul.ListOptions li").forEach { serverList ->
            val movieID = serverList.attr("data-id")
            val serverID = serverList.attr("data-key")
            val type = if (response.request.url.toString().contains("movies")) 1 else 2
            val url = "$baseUrl/?trembed=$serverID&trid=$movieID&trtype=$type"
            val langTag = serverList.selectFirst("p.AAIco-language").text()
            val lang = if (langTag.contains("LATINO")) "LAT" else if (langTag.contains("CASTELLANO")) "CAST" else "SUB"
            Log.i("bruh server", url)
            var request = client.newCall(GET(url)).execute()
            if (request.isSuccessful) {
                val serverLinks = request.asJsoup()
                serverLinks.select("div.Video iframe").map {
                    val iframe = it.attr("src")
                    if (iframe.contains("https://sc.seriesflix.video/index.php")) {
                        val postKey = iframe.replace("https://sc.seriesflix.video/index.php?h=", "")
                        Log.i("bruh frame", postKey)
                        val mediaType = ("application/x-www-form-urlencoded").toMediaType()
                        val body: RequestBody = "h=$postKey".toRequestBody(mediaType)
                        val newClient = OkHttpClient().newBuilder().build()
                        val requestServer = Request.Builder()
                            .url("https://sc.seriesflix.video/r.php?h=$postKey")
                            .method("POST", body)
                            .addHeader("Host", "sc.seriesflix.video")
                            .addHeader(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                            )
                            .addHeader(
                                "Accept",
                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                            )
                            .addHeader("Accept-Language", "en-US,en;q=0.5")
                            .addHeader("Content-Type", "application/x-www-form-urlencoded")
                            .addHeader("Origin", "null")
                            .addHeader("DNT", "1")
                            .addHeader("Connection", "keep-alive")
                            .addHeader("Upgrade-Insecure-Requests", "1")
                            .addHeader("Sec-Fetch-Dest", "iframe")
                            .addHeader("Sec-Fetch-Mode", "navigate")
                            .addHeader("Sec-Fetch-Site", "same-origin")
                            .addHeader("Sec-Fetch-User", "?1")
                            .addHeader("Alt-Used", "sc.seriesflix.video")
                            .build()
                        val document = newClient.newCall(requestServer).execute()
                        if (document.isSuccessful) {
                            
                            Log.i("bruh headers", document.networkResponse!!.headers.toString())
                            // Log.i("bruh body", document.body().toString())
                            document.request.headers.forEach { link ->
                                if (link.first.contains("location")) Log.i("bruh link", link.second)
                            }
                        } else {
                            Log.i("bruh error", document.message)
                        }
                    }
                }
            }
            // nuploadExtractor(lang, url)!!.forEach { video -> videoList.add(video) }
        }
        return videoList
    }
}

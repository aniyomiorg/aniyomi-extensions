package eu.kanade.tachiyomi.animeextension.pt.meusanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

class MeusAnimesExtractor(private val client: OkHttpClient) {

    fun head(url: String, headers: Headers) = GET(url, headers).newBuilder().head().build()

    fun videoListFromElement(element: Element): List<Video> {
        val headers = Headers.headersOf("Range", "bytes=0-1")
        val urls = buildMap {
            val sdUrl = element.attr("src")
            put("SD", sdUrl)
            val hdUrl = sdUrl.replace("/sd/", "/hd/")
            runCatching {
                // Check if the url is playing
                client.newCall(head(hdUrl, headers)).execute()
                put("HD", hdUrl)
            }
        }
        return urls.map { (quality, url) -> Video(url, quality, url) }
    }
}

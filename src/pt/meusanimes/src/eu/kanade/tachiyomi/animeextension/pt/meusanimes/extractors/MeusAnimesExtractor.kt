package eu.kanade.tachiyomi.animeextension.pt.meusanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import java.net.ProtocolException

class MeusAnimesExtractor(private val client: OkHttpClient) {

    fun HEAD(url: String, headers: Headers) = GET(url, headers).newBuilder().head().build()

    fun videoListFromElement(element: Element): List<Video> {
        val headers = Headers.headersOf("Range", "bytes=0-1")
        val urls = mutableMapOf<String, String>().apply {
            val sdUrl = element.attr("src")
            put("SD", sdUrl)
            val hdUrl = sdUrl.replace("/sd/", "/hd/")
            try {
                val testIt = client.newCall(HEAD(hdUrl, headers)).execute()
                put("HD", hdUrl)
            } catch (e: ProtocolException) {}
        }
        return urls.map { (quality, url) -> Video(url, quality, url) }
    }
}

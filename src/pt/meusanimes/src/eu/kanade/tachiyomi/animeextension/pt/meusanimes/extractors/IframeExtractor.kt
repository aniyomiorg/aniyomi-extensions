package eu.kanade.tachiyomi.animeextension.pt.meusanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

class IframeExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videoListFromIframe(iframeElement: Element): List<Video> {
        val iframeUrl = iframeElement.attr("src")
        val response = client.newCall(GET(iframeUrl, headers)).execute()
        val html = response.body.string()
        val url = html.substringAfter("play_url")
            .substringAfter(":\"")
            .substringBefore("\"")
        val video = Video(url, "Default", url, headers = headers)
        return listOf(video)
    }
}

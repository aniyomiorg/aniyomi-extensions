package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animeextension.pt.animefire.AFConstants
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class IframeExtractor(private val client: OkHttpClient) {

    private val headers = Headers.headersOf("User-Agent", AFConstants.USER_AGENT)

    fun videoListFromDocument(doc: Document): List<Video> {
        val iframeElement = doc.selectFirst("div#div_video iframe")
        val iframeUrl = iframeElement.attr("src")
        val response = client.newCall(GET(iframeUrl, headers)).execute()
        val html = response.body?.string().orEmpty()
        val url = html.substringAfter("play_url")
            .substringAfter(":\"")
            .substringBefore("\"")
        val video = Video(url, "Default", url, headers = headers)
        return listOf(video)
    }
}

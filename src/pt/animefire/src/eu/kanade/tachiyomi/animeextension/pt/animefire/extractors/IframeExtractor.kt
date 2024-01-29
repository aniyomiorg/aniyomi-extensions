package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class IframeExtractor(private val client: OkHttpClient) {
    fun videoListFromDocument(doc: Document, headers: Headers): List<Video> {
        val iframeElement = doc.selectFirst("div#div_video iframe")!!
        val iframeUrl = iframeElement.attr("src")
        val response = client.newCall(GET(iframeUrl, headers)).execute()
            .body.string()
        val url = response.substringAfter("play_url")
            .substringAfter(":\"")
            .substringBefore("\"")
        val video = Video(url, "Default", url, headers = headers)
        return listOf(video)
    }
}

package eu.kanade.tachiyomi.animeextension.pt.animestc.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class SendcmExtractor(private val client: OkHttpClient) {
    private val PLAYER_NAME = "Sendcm"

    fun videoFromUrl(url: String, quality: String): Video? {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val videoUrl = doc.selectFirst("video#vjsplayer > source")?.attr("src")
        return videoUrl?.let {
            val headers = Headers.headersOf("Referer", url)
            Video(it, "$PLAYER_NAME - $quality", it, headers = headers)
        }
    }
}

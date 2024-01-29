package eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class RuplayExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        return client.newCall(GET(url)).execute()
            .body.string()
            .substringAfter("Playerjs({")
            .substringAfter("file:\"")
            .substringBefore("\"")
            .split(",")
            .map {
                val videoUrl = it.substringAfter("]")
                val quality = it.substringAfter("[").substringBefore("]")
                val headers = Headers.headersOf("Referer", videoUrl)
                Video(videoUrl, "Ruplay - $quality", videoUrl, headers = headers)
            }
    }
}

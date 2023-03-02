package eu.kanade.tachiyomi.animeextension.de.movie4k.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamZExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): Video? {
        val link = client.newCall(GET(url)).execute().request.url.toString()
        val dllpart = link.substringAfter("/x")
        val videoUrl = client.newCall(
            GET(
                "https://get.streamz.tw/getlink-$dllpart.dll",
                headers = Headers.headersOf("referer", "https://streamz.ws/", "accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5", "range", "bytes=0-"),
            ),
        )
            .execute().request.url.toString()
        return Video(url, quality, videoUrl)
    }
}

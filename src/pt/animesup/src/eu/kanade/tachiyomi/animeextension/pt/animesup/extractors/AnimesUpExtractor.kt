package eu.kanade.tachiyomi.animeextension.pt.animesup.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimesUpExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String, headers: Headers): Video {
        val body = client.newCall(GET(url, headers))
            .execute()
            .body?.string()
            .orEmpty()
        val videoUrl = body.substringAfter("file: \"").substringBefore("\",")
        val newHeaders = Headers.headersOf("referer", url)
        return Video(url, quality, videoUrl, newHeaders)
    }
}

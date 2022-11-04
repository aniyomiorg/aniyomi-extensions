package eu.kanade.tachiyomi.animeextension.de.movie4k.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class StreamZExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): Video? {
        val link = client.newCall(GET(url)).execute().request.url.toString()
        val dllpart = link.substringAfter("/x")
        val videoUrl = client.newCall(GET("https://get.streamz.tw/getlink-$dllpart.dll")).execute().request.url.toString()
        return Video(url, quality, videoUrl)
    }
}

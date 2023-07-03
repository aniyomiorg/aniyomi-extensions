package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class MyStreamExtractor {

    fun videosFromUrl(
        url: String,
        headers: Headers,
    ): List<Video> {
        val host = url.substringBefore("/watch")

        val client = OkHttpClient()

        return runCatching {
            val response = client.newCall(GET(url)).execute()

            val document = response.asJsoup().html()
            val streamCode = document
                .substringAfter("${url.substringAfter("?v=")}\", \"")
                .substringBefore("\",null,null")
            val streamUrl = "$host/m3u8/$streamCode/master.txt?s=1&cache=1"

            val cookie = response.headers.firstOrNull {
                it.first.startsWith("set-cookie", true) && it.second.startsWith("PHPSESSID", true)
            }?.second?.substringBefore(";") ?: ""

            val newHeaders = headers.newBuilder()
                .set("cookie", cookie)
                .set("accept", "*/*")
                .build()

            val masterPlaylist = client.newCall(GET(streamUrl, newHeaders))
                .execute()
                .use { it.body.string() }

            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val resolution = it.substringAfter("RESOLUTION=")
                    .substringBefore("\n")
                    .substringAfter("x")
                    .substringBefore(",") + "p"
                val quality = ("MyStream: $resolution")
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, headers = newHeaders)
            }
        }.getOrNull() ?: emptyList<Video>()
    }
}

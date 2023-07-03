package eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidYardExtractor(private val client: OkHttpClient) {
    companion object {
        private const val VIDYARD_URL = "https://play.vidyard.com"
    }

    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val newHeaders = headers.newBuilder().add("Referer", VIDYARD_URL).build()
        val id = url.substringAfter("com/").substringBefore("?")
        val playerUrl = "$VIDYARD_URL/player/" + id + ".json"
        val callPlayer = client.newCall(GET(playerUrl, newHeaders)).execute()
            .use { it.body.string() }
        val data = callPlayer.substringAfter("hls\":[").substringBefore("]")
        val sources = data.split("profile\":\"").drop(1)

        return sources.map { source ->
            val src = source.substringAfter("url\":\"").substringBefore("\"")
            val quality = source.substringBefore("\"")
            Video(src, quality, src, headers = newHeaders)
        }
    }
}

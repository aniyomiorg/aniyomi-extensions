package eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidYardExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val newHeaders by lazy {
        headers.newBuilder().set("Referer", VIDYARD_URL).build()
    }

    fun videosFromUrl(url: String): List<Video> {
        val id = url.substringAfter("com/").substringBefore("?")
        val playerUrl = "$VIDYARD_URL/player/$id.json"
        val callPlayer = client.newCall(GET(playerUrl, newHeaders)).execute()
            .body.string()

        val data = callPlayer.substringAfter("hls\":[").substringBefore("]")
        val sources = data.split("profile\":\"").drop(1)

        return sources.map { source ->
            val src = source.substringAfter("url\":\"").substringBefore("\"")
            val quality = source.substringBefore("\"")
            Video(src, quality, src, headers = newHeaders)
        }
    }
}

private const val VIDYARD_URL = "https://play.vidyard.com"

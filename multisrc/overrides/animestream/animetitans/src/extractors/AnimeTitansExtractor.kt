package eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimeTitansExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, baseUrl: String): List<Video> {
        val newHeaders = headers.newBuilder().add("Referer", baseUrl).build()
        val callPlayer = client.newCall(GET(url)).execute().use { it.asJsoup() }
        val masterUrl = callPlayer.data().substringAfter("source: \"").substringBefore("\",")
        val domain = masterUrl.substringBefore("/videowl") // .replace("https", "http")
        val masterPlaylist = client.newCall(GET(masterUrl, newHeaders)).execute()
            .use { it.body.string() }

        val separator = "#EXT-X-STREAM-INF:"
        return masterPlaylist.substringAfter(separator).split(separator).map {
            val resolution = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p"
            val quality = "AnimeTitans: $resolution"
            val videoUrl = domain + it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, quality, videoUrl, headers = newHeaders)
        }
    }
}

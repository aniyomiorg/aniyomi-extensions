package eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimeTitansExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val callPlayer = client.newCall(GET(url)).execute().asJsoup()
        val masterUrl = callPlayer.data().substringAfter("source: \"").substringBefore("\",")
        val domain = masterUrl.substringBefore("/videowl") // .replace("https", "http")
        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
        val videoList = mutableListOf<Video>()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            val quality = "AnimeTitans: " + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p"
            val videoUrl = "$domain" + it.substringAfter("\n").substringBefore("\n")
            videoList.add(Video(videoUrl, quality, videoUrl, headers = headers))
        }
        return videoList
    }
}

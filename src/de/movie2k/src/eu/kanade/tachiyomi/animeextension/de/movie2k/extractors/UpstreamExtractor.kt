package eu.kanade.tachiyomi.animeextension.de.movie2k.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UpstreamExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String): MutableList<Video>? {
        try {
            val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)")!!.data()
            val masterUrl = JsUnpacker.unpackAndCombine(jsE).toString()
                .substringAfter("{file:\"").substringBefore("\"}")
            val masterBase = masterUrl.substringBefore("master")
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = "Upstream:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                    val videoUrl = masterBase + it.substringAfter("\n").substringBefore("\n")
                    videoList.add(Video(videoUrl, quality, videoUrl, headers = Headers.headersOf("origin", "https://upstream.to", "referer", "https://upstream.to/")))
                }
            return videoList
        } catch (e: Exception) {
            return null
        }
    }
}

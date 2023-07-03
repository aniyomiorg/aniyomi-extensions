package eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class UpstreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        try {
            val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)")!!.data()
            val masterUrl = JsUnpacker.unpackAndCombine(jsE)!!
                .substringAfter("{file:\"").substringBefore("\"}")
            val masterBase = masterUrl.substringBefore("master")
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = "Upstream - " + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                    val videoUrl = masterBase + it.substringAfter("\n").substringBefore("\n")
                    val videoHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Host", videoUrl.toHttpUrl().host)
                        .add("Origin", "https://upstream.to")
                        .add("Referer", "https://upstream.to/")
                        .build()

                    videoList.add(Video(videoUrl, quality, videoUrl, headers = videoHeaders))
                }
            return videoList
        } catch (e: Exception) {
            return emptyList()
        }
    }
}

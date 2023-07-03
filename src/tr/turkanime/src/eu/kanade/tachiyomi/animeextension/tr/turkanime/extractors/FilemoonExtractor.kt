package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class FilemoonExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)")!!.data()
        val masterUrl = JsUnpacker.unpackAndCombine(jsE)?.substringAfter("{file:\"")
            ?.substringBefore("\"}") ?: return emptyList()
        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()
        val videoList = mutableListOf<Video>()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
            .forEach {
                val quality = "Filemoon:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                val videoUrl = it.substringAfter("\n").substringBefore("\n")

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", videoUrl.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                videoList.add(Video(videoUrl, prefix + quality, videoUrl, headers = videoHeaders))
            }
        return videoList
    }
}

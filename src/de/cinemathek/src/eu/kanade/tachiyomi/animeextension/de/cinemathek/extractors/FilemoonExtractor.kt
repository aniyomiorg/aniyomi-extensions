package eu.kanade.tachiyomi.animeextension.de.cinemathek.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class FilemoonExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String): MutableList<Video>? {
        try {

            val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)").data()
            val masterUrl = JsUnpacker(jsE).unpack().toString()
                .substringAfter("{file:\"").substringBefore("\"}")
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = "Filemoon:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")
                    videoList.add(Video(videoUrl, quality, videoUrl))
                }
            return videoList
        } catch (e: Exception) {
            return null
        }
    }
}

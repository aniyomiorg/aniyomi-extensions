package eu.kanade.tachiyomi.lib.streamdavextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamDavExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        return document.select("source").map {
            val videoUrl = it.attr("src")
            val quality = it.attr("label")
            Video(url, "${prefix}StreamDav - ($quality)", videoUrl)
        }
    }
}

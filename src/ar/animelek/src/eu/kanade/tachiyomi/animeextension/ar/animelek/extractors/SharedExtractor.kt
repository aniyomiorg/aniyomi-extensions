package eu.kanade.tachiyomi.animeextension.ar.animelek.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class SharedExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val check = document.select("div.error4shared").text()
        val videoUrl = document.select("source").attr("src")
        return if (check.contains("This file is not available any more")) {
            Video(url, "no 1video", "https")
        } else {
            Video(url, quality, videoUrl)
        }
    }
}

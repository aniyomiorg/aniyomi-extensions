package eu.kanade.tachiyomi.animeextension.ar.movizland.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class UQLoadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val check = document.selectFirst("script:containsData(sources)").data()
        val videoUrl = check.substringAfter("sources: [\"").substringBefore("\"")
        return if (check.contains("sources")) {
            Video(url, quality, videoUrl)
        } else {
            Video(url, "no 1video", "https")
        }
    }
}

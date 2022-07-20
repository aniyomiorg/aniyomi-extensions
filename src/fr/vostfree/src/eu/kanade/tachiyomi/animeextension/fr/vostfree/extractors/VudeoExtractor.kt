package eu.kanade.tachiyomi.animeextension.fr.vostfree.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VudeoExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("sources: [\"")) {
                val videoUrl = script.data().substringAfter("sources: [\"").substringBefore("\"],")
                val quality = "Vudeo"
                videoList.add(Video(videoUrl, quality, videoUrl, null))
            }
        }
        return videoList
    }
}

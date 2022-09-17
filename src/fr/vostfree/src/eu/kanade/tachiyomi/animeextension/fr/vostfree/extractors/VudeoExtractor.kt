package eu.kanade.tachiyomi.animeextension.fr.vostfree.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VudeoExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script:containsData(sources: [)").forEach { script ->
            val videoUrl = script.data().substringAfter("sources: [").substringBefore("]").replace("\"", "").split(",")
            videoUrl.forEach {
                videoList.add(Video(it, "Vudeo", it, headers))
            }
        }
        return videoList
    }
}

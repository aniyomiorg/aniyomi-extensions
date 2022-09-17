package eu.kanade.tachiyomi.animeextension.fr.vostfree.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class MytvExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("CreatePlayer(\"v")) {
                val videosString = script.data().toString()
                val videoUrl = videosString.substringAfter("\"v=").substringBefore("\\u0026tp=video").replace("%26", "&").replace("%3a", ":").replace("%2f", "/").replace("%3f", "?").replace("%3d", "=")
                if (!videoUrl.contains("https:")) {
                    val videoUrl = "https:$videoUrl"
                    videoList.add(Video(videoUrl, "Stream", videoUrl))
                } else {
                    videoList.add(Video(videoUrl, "Mytv", videoUrl))
                }
            }
        }
        return videoList
    }
}

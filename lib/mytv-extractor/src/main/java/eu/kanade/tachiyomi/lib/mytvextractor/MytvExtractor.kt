package eu.kanade.tachiyomi.lib.mytvextractor

import okhttp3.OkHttpClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.network.GET
import java.net.URLDecoder

class MytvExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val doc = client.newCall(GET(url)).execute()
            .use { it.asJsoup() }

        val script = doc.selectFirst("script:containsData(createPlayer), script:containsData(CreatePlayer)")
            ?.data()
            ?: return emptyList()

        val videoUrl = script.substringAfter("\"v=").substringBefore("\\u0026tp=video")
            .let { URLDecoder.decode(it, "utf-8") }

        return if (!videoUrl.startsWith("https:")) {
            val newUrl = "https:$videoUrl"
            listOf(Video(newUrl, "${prefix}Stream", newUrl))
        } else {
            listOf(Video(videoUrl, "${prefix}Mytv", videoUrl))
        }

    }
}

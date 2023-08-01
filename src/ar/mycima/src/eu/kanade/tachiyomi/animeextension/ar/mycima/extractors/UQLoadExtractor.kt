package eu.kanade.tachiyomi.animeextension.ar.mycima.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class UQLoadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val check = document.selectFirst("script:containsData(sources)")!!.data()
        val videoUrl = check.substringAfter("sources: [\"").substringBefore("\"")
        return when{
            "soruces" in check -> Video(videoUrl, "UQLoad Mirror", videoUrl).let(::listOf)
            else -> emptyList()
        }
    }
}

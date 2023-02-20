package eu.kanade.tachiyomi.animeextension.pt.subanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class SubAnimesExtractor(private val client: OkHttpClient) {

    fun videoListFromUrl(url: String, player: String, headers: Headers): List<Video> {
        val playerUrl = url.replace("&p=true", "")
        val iframeBody = client.newCall(GET(playerUrl)).execute().asJsoup()
        val newHeaders = headers.newBuilder().set("Referer", playerUrl).build()
        val script = iframeBody.selectFirst("script:containsData(addButton)").data()
        return if (script.contains("vSources")) {
            val sources = script.substringAfter("vSources").substringBefore(";")
            sources.split("src\":").drop(1).map {
                val videoUrl = it.substringAfter("\"")
                    .substringBefore("\"")
                    .replace("\\", "")
                    .trim()
                val quality = it.substringAfter("size\":").substringBefore("}")
                Video(videoUrl, "$player - ${quality}p", videoUrl, newHeaders)
            }
        } else {
            val videoUrl = script.substringAfter("file:")
                .substringAfter("'")
                .substringBefore("'")
                .trim()
            listOf(Video(videoUrl, player, videoUrl, newHeaders))
        }
    }
}

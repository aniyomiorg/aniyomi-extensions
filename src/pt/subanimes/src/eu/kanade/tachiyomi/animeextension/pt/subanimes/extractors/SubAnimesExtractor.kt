package eu.kanade.tachiyomi.animeextension.pt.subanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class SubAnimesExtractor(private val client: OkHttpClient) {

    fun videoListFromUrl(url: String, player: String, headers: Headers): List<Video> {
        val iframeBody = client.newCall(GET(url)).execute().asJsoup()
        val newHeaders = headers.newBuilder().set("Referer", url).build()
        val script = iframeBody.selectFirst("script:containsData(addButton)").data()
        return if (script.contains("vSources")) {
            val sources = script.substringAfter("vSources").substringBefore(";")
            sources.split("src\":").drop(1).map {
                val url = it.substringAfter("\"")
                    .substringBefore("\"")
                    .replace("\\", "")
                    .trim()
                val quality = it.substringAfter("size\":").substringBefore("}")
                Video(url, "$player - ${quality}p", url, headers)
            }
        } else {
            val url = script.substringAfter("file:")
                .substringAfter("'")
                .substringBefore("'")
                .trim()
            listOf(Video(url, player, url, newHeaders))
        }
    }
}

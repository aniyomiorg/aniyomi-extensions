package eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VoeExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.selectFirst("script:containsData(const sources)")
            ?.data()
            ?: return null
        val videoUrl = script.substringAfter("hls\": \"").substringBefore("\"")
        val quality = script.substringAfter("video_height\": ")
            .substringBefore(",")
        val qualityStr = "VoeCDN(${quality}p)"
        return Video(videoUrl, qualityStr, videoUrl, null)
    }
}

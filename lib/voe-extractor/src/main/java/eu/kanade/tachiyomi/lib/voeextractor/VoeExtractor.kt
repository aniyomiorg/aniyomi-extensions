package eu.kanade.tachiyomi.lib.voeextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VoeExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String? = null, prefix: String = ""): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.selectFirst("script:containsData(const sources), script:containsData(var sources)")
            ?.data()
            ?: return null
        val videoUrl = script.substringAfter("hls': '").substringBefore("'")
        val resolution = script.substringAfter("video_height': ").substringBefore(",")
        val qualityStr = when {
            prefix.isNotEmpty() -> "$prefix${resolution}p"
            else -> quality ?: "VoeCDN(${resolution}p)"
        }
        return Video(url, qualityStr, videoUrl)
    }

    fun videosFromUrl(url: String, quality: String? = null, prefix: String = ""): List<Video> {
        return videoFromUrl(url, quality, prefix)?.let(::listOf).orEmpty()
    }
}

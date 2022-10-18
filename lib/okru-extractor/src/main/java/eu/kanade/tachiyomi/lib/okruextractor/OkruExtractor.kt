package eu.kanade.tachiyomi.lib.okruextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient) {

    private fun fixQuality(quality: String): String {
        val qualities = listOf(
            Pair("full", "1080p"),
            Pair("hd", "720p"),
            Pair("sd", "480p"),
            Pair("low", "360p"),
            Pair("lowest", "240p"),
            Pair("mobile", "144p")
        )
        return qualities.find { it.first == quality }?.second ?: quality
    }

    fun videosFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videosString = document.selectFirst("div[data-options]")
            ?.attr("data-options")
            ?.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            ?.substringBefore("]")
            ?: return emptyList<Video>()
        return videosString.split("{\\\"name\\\":\\\"").reversed().mapNotNull {
            val videoUrl = it.substringAfter("url\\\":\\\"")
                .substringBefore("\\\"")
                .replace("\\\\u0026", "&")
            val quality = it.substringBefore("\\\"").let {
                if (fixQualities) fixQuality(it)
                else it
            }
            val videoQuality = ("Okru:" + quality).let {
                if (prefix.isNotBlank()) "$prefix $it"
                else it
            }
            if (videoUrl.startsWith("https://"))
                Video(videoUrl, videoQuality, videoUrl)
            else null
        }
    }
}

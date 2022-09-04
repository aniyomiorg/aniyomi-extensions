package eu.kanade.tachiyomi.animeextension.es.hentaijk.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, qualityPrefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        return try {
            val document = client.newCall(GET(url)).execute().asJsoup()
            val qualities = listOf(
                Pair("full", "1080p"),
                Pair("hd", "720p"),
                Pair("sd", "480p"),
                Pair("low", "360p"),
                Pair("lowest", "240p"),
                Pair("mobile", "144p")
            )
            val videosString = document.select("div[data-options]").attr("data-options")
                .substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
                .substringBefore("]")
            videosString.split("{\\\"name\\\":\\\"").reversed().forEach {
                val videoUrl = it.substringAfter("url\\\":\\\"")
                    .substringBefore("\\\"")
                    .replace("\\\\u0026", "&")
                val quality = try { qualities.find { q -> q.first == it.substringBefore("\\\"") }?.second } catch (e: Exception) { it.substringBefore("\\\"") }
                val videoQuality = qualityPrefix + "Okru:" + quality
                if (videoUrl.startsWith("https://")) {
                    videoList.add(Video(videoUrl, videoQuality, videoUrl, headers = null))
                }
            }
            videoList
        } catch (e: Exception) {
            videoList
        }
    }
}

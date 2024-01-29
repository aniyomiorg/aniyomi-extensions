package eu.kanade.tachiyomi.lib.uqloadextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources:)")?.data()
            ?: return emptyList()

        val videoUrl = script.substringAfter("sources: [\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.takeIf { it.startsWith("http") }
            ?: return emptyList()

        val videoHeaders = Headers.headersOf("Referer", "https://uqload.co/")
        val quality = if (prefix.isNotBlank()) "$prefix Uqload" else "Uqload"

        return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
    }
}

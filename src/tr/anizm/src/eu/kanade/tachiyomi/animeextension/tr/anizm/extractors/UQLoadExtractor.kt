package eu.kanade.tachiyomi.animeextension.tr.anizm.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UQLoadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String = "uqload"): Video? {
        val document = client.newCall(GET(url)).execute().use { it.asJsoup() }
        val check = document.selectFirst("script:containsData(sources)")?.data()
            ?: return null
        val videoUrl = check.substringAfter("sources: [\"", "").substringBefore("\"", "")
        if (videoUrl.isBlank()) return null
        val videoHeaders = Headers.headersOf("Referer", url)
        return Video(videoUrl, quality, videoUrl, videoHeaders)
    }
}

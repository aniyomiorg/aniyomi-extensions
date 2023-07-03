package eu.kanade.tachiyomi.lib.youruploadextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class YourUploadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, headers: Headers, name: String = "YourUpload", prefix: String = ""): List<Video> {
        val newHeaders = headers.newBuilder().add("referer", "https://www.yourupload.com/").build()
        return runCatching {
            val request = client.newCall(GET(url, headers = newHeaders)).execute()
            val document = request.asJsoup()
            val baseData = document.selectFirst("script:containsData(jwplayerOptions)")?.data()
            if (!baseData.isNullOrEmpty()) {
                val basicUrl = baseData.substringAfter("file: '").substringBefore("',")
                val quality = prefix + name
                listOf(Video(basicUrl, quality, basicUrl, headers = newHeaders))
            } else {
                null
            }
        }.getOrNull() ?: emptyList<Video>()
    }
}

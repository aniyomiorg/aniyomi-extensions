package eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VoeExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.select("script:containsData(const sources = {)")
            .firstOrNull()?.data()?.substringAfter("\"hls\": \"") ?: return null
        val videoUrl = script.substringAfter("\"hls\": \"").substringBefore("\",")
        return Video(url, quality, videoUrl, null)
    }
}

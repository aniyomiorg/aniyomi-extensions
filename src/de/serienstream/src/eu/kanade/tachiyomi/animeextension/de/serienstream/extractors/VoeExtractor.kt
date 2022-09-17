package eu.kanade.tachiyomi.animeextension.de.serienstream.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VoeExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.select("script:containsData(function d04ad2e48229ae25a282e15c7c2f69a2(dea04c5949242bfd216e35def894b930))")
            .firstOrNull()?.data()?.substringAfter("\"hls\": \"") ?: return null
        val videoUrl = script.substringAfter("\"hls\": \"").substringBefore("\",")
        return Video(url, quality, videoUrl)
    }
}

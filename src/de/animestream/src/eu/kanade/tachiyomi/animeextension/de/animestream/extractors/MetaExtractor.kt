package eu.kanade.tachiyomi.animeextension.de.animestream.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class MetaExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.select("script:containsData(sources: [{src:)")
            .firstOrNull()?.data()?.substringAfter("sources: [{src: \"") ?: return null
        val videoUrl = script.substringAfter("sources: [{src: \"").substringBefore("\", type:")
        return Video(url, quality, videoUrl)
    }
}

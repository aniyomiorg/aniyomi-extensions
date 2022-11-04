package eu.kanade.tachiyomi.animeextension.de.movie4k.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VidozaExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.select("script:containsData(window.pData = {)")
            .firstOrNull()?.data()?.substringAfter("sourcesCode: [{ src: \"") ?: return null
        val videoUrl = script.substringAfter("sourcesCode: [{ src: \"").substringBefore("\", type:")
        return Video(url, quality, videoUrl)
    }
}

package eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class SharedExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        return document.selectFirst("source")?.let {
            Video(it.attr("src"), quality, it.attr("src"))
        }
    }
}

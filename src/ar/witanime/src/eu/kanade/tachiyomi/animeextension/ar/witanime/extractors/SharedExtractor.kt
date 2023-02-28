package eu.kanade.tachiyomi.animeextension.ar.witanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class SharedExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, quality: String = "mirror"): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        return document.selectFirst("source")?.let {
            Video(it.attr("src"), "4Shared: $quality", it.attr("src"))
        }
    }
}

package eu.kanade.tachiyomi.animeextension.es.legionanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class MediaFireExtractor(
    private val client: OkHttpClient,
) {
    fun getVideoFromUrl(url: String, prefix: String = ""): Video? {
        val document = client.newCall(GET(url)).execute()
        val downloadUrl = document.asJsoup().selectFirst("a#downloadButton")?.attr("href")
        if (!downloadUrl.isNullOrBlank()) {
            return Video(downloadUrl, "$prefix-MediaFire", downloadUrl)
        }
        return null
    }
}

package eu.kanade.tachiyomi.animeextension.pt.animestc.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class AnonFilesExtractor(private val client: OkHttpClient) {
    private val PLAYER_NAME = "AnonFiles"

    fun videoFromUrl(url: String, quality: String): Video? {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val downloadUrl = doc.selectFirst("a#download-url")?.attr("href")
        return downloadUrl?.let {
            Video(it, "$PLAYER_NAME - $quality", it)
        }
    }
}

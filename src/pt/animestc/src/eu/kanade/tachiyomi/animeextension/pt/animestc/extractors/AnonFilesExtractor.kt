package eu.kanade.tachiyomi.animeextension.pt.animestc.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class AnonFilesExtractor(private val client: OkHttpClient) {
    private val playerName = "AnonFiles"

    fun videosFromUrl(url: String, quality: String): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val downloadUrl = doc.selectFirst("a#download-url")?.attr("href")
        return downloadUrl?.let {
            listOf(Video(it, "$playerName - $quality", it))
        }.orEmpty()
    }
}

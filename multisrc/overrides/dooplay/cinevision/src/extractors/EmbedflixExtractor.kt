package eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class EmbedflixExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val newUrl = url.replace("/e/", "/api.php?action=getAds&key=0&lang=DUB&s=")
        val iframeUrl = client.newCall(GET(newUrl)).execute()
            .use { it.asJsoup() }
            .selectFirst("iframe")
            ?.attr("src") ?: return emptyList()

        val playerData = client.newCall(GET(iframeUrl)).execute()
            .use { it.body.string() }
            .let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val masterUrl = playerData.substringAfter("file:\"").substringBefore('"')

        val playlistData = client.newCall(GET(masterUrl)).execute()
            .use { it.body.string() }

        val separator = "#EXT-X-STREAM-INF:"
        return playlistData.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, "EmbedFlix(DUB) - $quality", videoUrl)
        }
    }
}

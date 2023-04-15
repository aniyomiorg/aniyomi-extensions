package eu.kanade.tachiyomi.animeextension.pt.megaflix.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class MegaflixExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, lang: String = ""): List<Video> {
        val unpacked = client.newCall(GET(url)).execute()
            .body.string()
            .let(JsUnpacker::unpackAndCombine)
            ?.replace("\\", "")
            ?: return emptyList()

        val playlistUrl = unpacked.substringAfter("file':'").substringBefore("'")
        val playlistBody = client.newCall(GET(playlistUrl)).execute().body.string()

        val separator = "#EXT-X-STREAM-INF"
        return playlistBody.substringAfter(separator).split(separator).map {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore("\n") + "p"
            val quality = "Megaflix($lang) - $resolution"
            val path = it.substringAfter("\n").substringBefore("\n")
            val videoUrl = playlistUrl.substringBeforeLast("/") + "/$path"

            Video(videoUrl, quality, videoUrl)
        }
    }
}

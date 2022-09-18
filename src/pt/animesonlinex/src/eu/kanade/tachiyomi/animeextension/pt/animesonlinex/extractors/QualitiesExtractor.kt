package eu.kanade.tachiyomi.animeextension.pt.animesonlinex.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class QualitiesExtractor(
    private val client: OkHttpClient,
    private val headers: Headers
) {

    fun getVideoList(url: String, qualityStr: String): List<Video> {
        val playlistBody = client.newCall(GET(url, headers)).execute()
            .body!!.string()

        val separator = "#EXT-X-STREAM-INF:"
        val playerName = qualityStr.substringBefore(" -")
        return playlistBody.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore("\n")
                .substringBefore(",") + "p"
            val path = it.substringAfter("\n").substringBefore("\n")

            val videoUrl = if (!path.startsWith("https:")) {
                url.substringBeforeLast("/") + "/$path"
            } else path
            Video(videoUrl, "$playerName - $quality", videoUrl, headers)
        }
    }
}

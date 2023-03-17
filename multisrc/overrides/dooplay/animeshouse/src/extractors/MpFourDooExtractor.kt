package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class MpFourDooExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val REGEX_MPDOO = Regex("file\":\"(.*?)\"")
    private val PLAYER_NAME = "Mp4Doo"

    fun getVideoList(js: String): List<Video> {
        val videoUrl = REGEX_MPDOO.find(js)!!.groupValues
            .get(1)
            .replace("fy..", "fy.v.")
        return if (videoUrl.endsWith("playlist.m3u8")) {
            val playlistBody = client.newCall(GET(videoUrl, headers)).execute()
                .use { it.body.string() }

            val separator = "#EXT-X-STREAM-INF:"
            playlistBody.substringAfter(separator).split(separator).map {
                val quality = PLAYER_NAME + " - " + it.substringAfter("RESOLUTION=")
                    .substringAfter("x")
                    .substringBefore("\n")
                    .substringBefore(",") + "p"

                val path = it.substringAfter("\n").substringBefore("\n")

                val playlistUrl = if (!path.startsWith("https:")) {
                    videoUrl.replace("playlist.m3u8", path)
                } else {
                    path
                }

                Video(playlistUrl, quality, playlistUrl, headers)
            }
        } else {
            listOf(Video(videoUrl, PLAYER_NAME, videoUrl, headers))
        }
    }
}

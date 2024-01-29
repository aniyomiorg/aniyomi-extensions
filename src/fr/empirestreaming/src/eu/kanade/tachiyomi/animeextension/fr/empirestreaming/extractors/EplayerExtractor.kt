package eu.kanade.tachiyomi.animeextension.fr.empirestreaming.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class EplayerExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val id = url.substringAfterLast("/")

        val postUrl = "$EPLAYER_HOST/player/index.php?data=$id&do=getVideo"
        val body = FormBody.Builder()
            .add("hash", id)
            .add("r", "")
            .build()

        val headers = Headers.headersOf(
            "X-Requested-With",
            "XMLHttpRequest",
            "Referer",
            EPLAYER_HOST,
            "Origin",
            EPLAYER_HOST,
        )

        val masterUrl = client.newCall(POST(postUrl, headers, body = body)).execute()
            .body.string()
            .substringAfter("videoSource\":\"")
            .substringBefore('"')
            .replace("\\", "")

        // TODO: Use playlist-utils
        val separator = "#EXT-X-STREAM-INF"
        return client.newCall(GET(masterUrl, headers)).execute()
            .body.string()
            .substringAfter(separator)
            .split(separator)
            .map {
                val resolution = it.substringAfter("RESOLUTION=")
                    .substringBefore("\n")
                    .substringAfter("x")
                    .substringBefore(",") + "p"

                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, "E-Player - $resolution", videoUrl, headers)
            }
    }

    companion object {
        private const val EPLAYER_HOST = "https://e-player-stream.app"
    }
}

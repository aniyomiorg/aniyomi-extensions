package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class GenericExtractor(
    private val client: OkHttpClient,
    private val headers: Headers
) {

    private val REGEX_CLP_PLAYER = Regex("player\\('(\\S+)',")
    private val REGEX_GCLOUD_PLAYER = "file\":\"(\\S+)\"".toRegex()
    private val REGEX_QUALITY = Regex("(?<=RESOLUTION=)\\d+x(\\d+).*?\n(https.*)")

    fun getVideoList(url: String, js: String): List<Video> {
        val (player, regex) = when {
            "gcloud" in url -> Pair("GCLOUD", REGEX_GCLOUD_PLAYER)
            else -> Pair("CLP", REGEX_CLP_PLAYER)
        }
        Log.e(player, url)

        val playlistUrl = regex.find(js)!!.groupValues.get(1)
        if ("m3u8.php" in playlistUrl) {
            val req = client.newCall(GET(playlistUrl, headers)).execute()
            val body = req.body?.string().orEmpty()
            val videos = REGEX_QUALITY.findAll(body).map {
                val quality = "$player: " + it.groupValues.get(1) + "p"
                val videoUrl = it.groupValues.get(2)
                Video(videoUrl, quality, videoUrl, headers)
            }.toList()
            if (videos.size > 0)
                return videos
        }

        return listOf(Video(playlistUrl, player, playlistUrl, headers))
    }
}

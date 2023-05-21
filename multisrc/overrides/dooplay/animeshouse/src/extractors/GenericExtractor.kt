package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class GenericExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val regexClpPlayer = Regex("player\\('(\\S+)',")
    private val regexGcloudPlayer = "file\":\"(\\S+)\"".toRegex()
    private val regexQuality = Regex("(?<=RESOLUTION=)\\d+x(\\d+).*?\n(https.*)")

    fun getVideoList(url: String, js: String): List<Video> {
        val (player, regex) = when {
            "gcloud" in url -> Pair("GCLOUD", regexGcloudPlayer)
            else -> Pair("CLP", regexClpPlayer)
        }

        val playlistUrl = regex.find(js)!!.groupValues.get(1)
        if ("m3u8.php" in playlistUrl) {
            client.newCall(GET(playlistUrl, headers)).execute().use { req ->
                val body = req.body.string()
                val videos = regexQuality.findAll(body).map {
                    val quality = "$player: " + it.groupValues.get(1) + "p"
                    val videoUrl = it.groupValues.get(2)
                    Video(videoUrl, quality, videoUrl, headers)
                }.toList()
                if (videos.size > 0) {
                    return videos
                }
            }
        }

        return listOf(Video(playlistUrl, player, playlistUrl, headers))
    }
}

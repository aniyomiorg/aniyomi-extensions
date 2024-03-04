package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient

class GenericExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val regexClpPlayer = Regex("player\\('(\\S+)',")
    private val regexGcloudPlayer = "file\":\"(\\S+)\"".toRegex()

    fun getVideoList(url: String, js: String): List<Video> {
        val (player, regex) = when {
            "gcloud" in url -> Pair("GCLOUD", regexGcloudPlayer)
            else -> Pair("CLP", regexClpPlayer)
        }

        val playlistUrl = regex.find(js)!!.groupValues.get(1)
        if ("m3u8.php" in playlistUrl) {
            return playlistUtils.extractFromHls(playlistUrl, videoNameGen = { "$player: $it" })
        }

        return listOf(Video(playlistUrl, player, playlistUrl, headers))
    }
}

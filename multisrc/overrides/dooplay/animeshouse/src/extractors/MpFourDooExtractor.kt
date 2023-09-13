package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient

class MpFourDooExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val regexMpdoo = Regex("file\":\"(.*?)\"")
    private val playerName = "Mp4Doo"
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun getVideoList(js: String): List<Video> {
        val videoUrl = regexMpdoo.find(js)!!.groupValues
            .get(1)
            .replace("fy..", "fy.v.")
        return if (videoUrl.endsWith("playlist.m3u8")) {
            playlistUtils.extractFromHls(videoUrl, videoNameGen = { "$playerName: $it" })
        } else {
            listOf(Video(videoUrl, playerName, videoUrl, headers))
        }
    }
}

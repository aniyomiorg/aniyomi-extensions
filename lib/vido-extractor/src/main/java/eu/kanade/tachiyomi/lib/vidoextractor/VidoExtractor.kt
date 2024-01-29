package eu.kanade.tachiyomi.lib.vidoextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class VidoExtractor(private val client: OkHttpClient) {
    companion object {
        private const val VIDO_URL = "https://pink.vido.lol"
        private val REGEX_ID = Regex("master\\|(.*?)\\|")
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute().body.string()
        val id = REGEX_ID.find(document)?.groupValues?.get(1)
        val masterUrl = "$VIDO_URL/hls/$id/master.m3u8"
        return playlistUtils.extractFromHls(masterUrl, videoNameGen = { "${prefix}Vido - ($it)" })
    }
}

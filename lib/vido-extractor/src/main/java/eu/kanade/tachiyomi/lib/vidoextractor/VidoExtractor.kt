package eu.kanade.tachiyomi.lib.vidoextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class VidoExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute().use { it.body.string() }
        val id = Regex("master\\|(.*?)\\|").find(document)?.groupValues?.get(1)
        val masterUrl = "https://pink.vido.lol/hls/${id}/master.m3u8"
        return PlaylistUtils(client).extractFromHls(masterUrl, videoNameGen = { "${prefix}Vido - (${it})" })
    }

}

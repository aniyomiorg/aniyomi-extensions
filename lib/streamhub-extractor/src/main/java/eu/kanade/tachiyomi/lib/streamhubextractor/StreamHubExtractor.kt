package eu.kanade.tachiyomi.lib.streamhubextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class StreamHubExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute().use { it.body.string() }
        val id = Regex("urlset\\|(.*?)\\|").find(document)?.groupValues?.get(1)
        val sub = Regex("width\\|(.*?)\\|").find(document)?.groupValues?.get(1)
        val masterUrl = "https://${sub}.streamhub.ink/hls/,${id},.urlset/master.m3u8"
        return PlaylistUtils(client).extractFromHls(masterUrl, videoNameGen = { "${prefix}StreamHub - (${it})" })
    }

}

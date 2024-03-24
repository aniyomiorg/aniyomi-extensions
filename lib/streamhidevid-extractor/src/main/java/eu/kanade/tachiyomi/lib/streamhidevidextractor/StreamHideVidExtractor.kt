package eu.kanade.tachiyomi.lib.streamhidevidextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class StreamHideVidExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val page = client.newCall(GET(url)).execute().body.string()
        val playlistUrl = (JsUnpacker.unpackAndCombine(page) ?: page)
            .substringAfter("sources:")
            .substringAfter("file:\"") // StreamHide
            .substringAfter("src:\"") // StreamVid
            .substringBefore('"')
        if (!playlistUrl.startsWith("http")) return emptyList()
        return playlistUtils.extractFromHls(playlistUrl,
            videoNameGen = { "${prefix}StreamHideVid - $it" }
        )
    }
}

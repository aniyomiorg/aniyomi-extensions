package eu.kanade.tachiyomi.lib.streamhidevidextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class StreamHideVidExtractor(private val client: OkHttpClient) {
    // from nineanime / ask4movie FilemoonExtractor
    private val subtitleRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val page = client.newCall(GET(url)).execute().body.string()
        val playlistUrl = (JsUnpacker.unpackAndCombine(page) ?: page)
            .substringAfter("sources:")
            .substringAfter("file:\"") // StreamHide
            .substringAfter("src:\"") // StreamVid
            .substringBefore('"')
        if (!playlistUrl.startsWith("http")) return emptyList()
        return PlaylistUtils(client).extractFromHls(playlistUrl,
            videoNameGen = { "${prefix}StreamHideVid - $it" }
        )
    }
}

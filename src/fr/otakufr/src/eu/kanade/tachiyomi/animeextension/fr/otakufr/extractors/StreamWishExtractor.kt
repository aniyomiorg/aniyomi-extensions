package eu.kanade.tachiyomi.animeextension.fr.otakufr.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers = headers)).execute().asJsoup()
        val jsEval = doc.selectFirst("script:containsData(m3u8)")?.data() ?: return emptyList()

        val masterUrl = JsUnpacker.unpackAndCombine(jsEval)
            ?.substringAfter("source")
            ?.substringAfter("file:\"")
            ?.substringBefore("\"")
            ?: return emptyList()

        return PlaylistUtils(client, headers).extractFromHls(masterUrl, videoNameGen = { quality -> "Streamwish - $quality" })
    }
}

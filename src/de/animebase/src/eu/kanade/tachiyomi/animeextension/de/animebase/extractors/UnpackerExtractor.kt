package eu.kanade.tachiyomi.animeextension.de.animebase.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UnpackerExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, hoster: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute()
            .asJsoup()

        val script = doc.selectFirst("script:containsData(eval)")
            ?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val playlistUrl = script.substringAfter("file:\"").substringBefore('"')

        return playlistUtils.extractFromHls(
            playlistUrl,
            referer = playlistUrl,
            videoNameGen = { "$hoster - $it" },
        )
    }
}

package eu.kanade.tachiyomi.animeextension.pt.megaflix.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class MegaflixExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, lang: String = ""): List<Video> {
        val unpacked = client.newCall(GET(url, headers)).execute()
            .body.string()
            .let(JsUnpacker::unpackAndCombine)
            ?.replace("\\", "")
            ?: return emptyList()

        val playlistUrl = unpacked.substringAfter("file':'").substringBefore("'")

        return playlistUtils.extractFromHls(
            playlistUrl,
            "https://megaflix.co",
            videoNameGen = { "Megaflix($lang) - $it" },
        )
    }
}

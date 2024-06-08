package eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class FireplayerExtractor(private val client: OkHttpClient, private val host: String = "https://embedplayer.online") {
    private val headers by lazy {
        Headers.headersOf(
            "X-Requested-With",
            "XMLHttpRequest",
            "Referer",
            host,
            "Origin",
            host,
        )
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, lang: String): List<Video> {
        var id = url.substringAfterLast("/")

        if (id.length < 32) {
            val doc = client.newCall(GET(url, headers)).execute().asJsoup()

            val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
                ?.let(JsUnpacker::unpackAndCombine)
                ?: doc.selectFirst("script:containsData(FirePlayer)")?.data()

            if (script?.contains("FirePlayer(") == true) {
                id = script.substringAfter("FirePlayer(\"").substringBefore('"')
            }
        }

        val postUrl = "$host/player/index.php?data=$id&do=getVideo"
        val body = FormBody.Builder()
            .add("hash", id)
            .add("r", "")
            .build()

        val masterUrl = client.newCall(POST(postUrl, headers, body = body)).execute()
            .body.string()
            .substringAfter("securedLink\":\"")
            .substringBefore('"')
            .replace("\\", "")

        return playlistUtils.extractFromHls(masterUrl, videoNameGen = { "[$lang] EmbedPlayer - $it" })
    }
}

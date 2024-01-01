package eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors

import android.util.Base64
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GoAnimesExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).execute()
            .use { it.body.string() }
        return when {
            "/profix/player.php" in url ->
                PlaylistExtractor.videosFromScript(body, PLAYER_NAME)
            "/proxy/v.php" in url -> {
                val playlistUrl = JsUnpacker.unpackAndCombine(body)
                    ?.substringAfterLast("player(\\'", "")
                    ?.substringBefore("\\'", "")
                    ?.takeIf(String::isNotEmpty)
                    ?: return emptyList()

                playlistUtils.extractFromHls(playlistUrl, url, videoNameGen = { "$PLAYER_NAME - $it" })
            }
            "/proxy/api3/" in url -> {
                val playlistUrl = body.substringAfter("sources:", "")
                    .substringAfter("file:", "")
                    .substringAfter("'", "")
                    .substringBefore("'", "")
                    .takeIf(String::isNotEmpty)
                    ?: return emptyList()

                val fixedUrl = if (playlistUrl.contains("/aHR0")) {
                    val encoded = playlistUrl.substringAfterLast("/").substringBefore(".")
                    String(Base64.decode(encoded, Base64.DEFAULT))
                } else {
                    playlistUrl
                }

                val referer = url.toHttpUrl().queryParameter("url") ?: url
                playlistUtils.extractFromHls(fixedUrl, referer, videoNameGen = { "$PLAYER_NAME - $it" })
            }
            else -> emptyList()
        }
    }
}

private const val PLAYER_NAME = "GoAnimes"

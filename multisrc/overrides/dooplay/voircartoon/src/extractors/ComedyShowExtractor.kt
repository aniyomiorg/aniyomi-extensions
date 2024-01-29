package eu.kanade.tachiyomi.animeextension.fr.voircartoon.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

// Based on EPlayerExtractor (pt/Pobreflix)
class ComedyShowExtractor(private val client: OkHttpClient) {
    private val headers by lazy {
        Headers.headersOf(
            "X-Requested-With",
            "XMLHttpRequest",
            "Referer",
            COMEDY_SHOW_HOST,
            "Origin",
            COMEDY_SHOW_HOST,
        )
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val id = url.substringAfterLast("/")

        val postUrl = "$COMEDY_SHOW_HOST/player/index.php?data=$id&do=getVideo"
        val body = FormBody.Builder()
            .add("hash", id)
            .add("r", "")
            .build()

        val masterUrl = client.newCall(POST(postUrl, headers, body = body)).execute()
            .body.string()
            .substringAfter("videoSource\":\"")
            .substringBefore('"')
            .replace("\\", "")

        return playlistUtils.extractFromHls(masterUrl, videoNameGen = { "ComedyShow - $it" })
    }

    companion object {
        private const val COMEDY_SHOW_HOST = "https://comedyshow.to"
    }
}

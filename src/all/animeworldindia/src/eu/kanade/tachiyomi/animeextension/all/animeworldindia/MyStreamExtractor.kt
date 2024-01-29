package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class MyStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, language: String): List<Video> {
        val host = url.substringBefore("/watch")

        return runCatching {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()

            val streamCode = body
                .substringAfter("sniff(") // Video function
                .substringAfter(", \"") // our beloved ID
                .substringBefore('"')

            val streamUrl = "$host/m3u8/$streamCode/master.txt?s=1&cache=1"

            val cookie = response.headers.firstOrNull {
                it.first.startsWith("set-cookie", true) && it.second.startsWith("PHPSESSID", true)
            }?.second?.substringBefore(";") ?: ""

            val newHeaders = headers.newBuilder()
                .set("cookie", cookie)
                .set("accept", "*/*")
                .build()

            playlistUtils.extractFromHls(
                streamUrl,
                masterHeaders = newHeaders,
                videoHeaders = newHeaders,
                videoNameGen = { "[$language] MyStream: $it" },
            )
        }.getOrElse { emptyList<Video>() }
    }
}

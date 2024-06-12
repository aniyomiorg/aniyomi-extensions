package eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

// From animeworldindia
class MyStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, language: String): List<Video> {
        val host = url.substringBefore("/watch?")

        val response = client.newCall(GET(url, headers)).execute()
        val body = response.body.string()

        val codePart = body
            .substringAfter("sniff(") // Video function
            .substringBefore(",[")

        val streamCode = codePart
            .substringAfterLast(",\"") // our beloved hash
            .substringBefore('"')

        val id = codePart.substringAfter(",\"").substringBefore('"') // required ID

        val streamUrl = "$host/m3u8/$id/$streamCode/master.txt?s=1&cache=1"

        val cookie = response.headers.firstOrNull {
            it.first.startsWith("set-cookie", true) && it.second.startsWith("PHPSESSID", true)
        }?.second?.substringBefore(";") ?: ""

        val newHeaders = headers.newBuilder()
            .set("cookie", cookie)
            .set("accept", "*/*")
            .build()

        return playlistUtils.extractFromHls(
            streamUrl,
            masterHeaders = newHeaders,
            videoHeaders = newHeaders,
            videoNameGen = { "[$language] MyStream: $it" },
        )
    }
}

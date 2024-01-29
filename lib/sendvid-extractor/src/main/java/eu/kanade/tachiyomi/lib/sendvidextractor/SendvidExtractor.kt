package eu.kanade.tachiyomi.lib.sendvidextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class SendvidExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val masterUrl = document.selectFirst("source#video_source")?.attr("src") ?: return emptyList()

        return if (masterUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(masterUrl, url, videoNameGen = { prefix + "Sendvid:$it" })
        } else {
            val httpUrl = "https://${url.toHttpUrl()}"
            val newHeaders = headers.newBuilder()
                .set("Origin", httpUrl)
                .set("Referer", "$httpUrl/")
                .build()
            listOf(Video(masterUrl, prefix + "Sendvid:default", masterUrl, newHeaders))
        }
    }
}

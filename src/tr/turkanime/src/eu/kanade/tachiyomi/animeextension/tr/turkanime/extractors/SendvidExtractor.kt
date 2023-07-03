package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class SendvidExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = client.newCall(GET(url)).execute().asJsoup()
        val masterUrl = document.selectFirst("source#video_source")?.attr("src") ?: return emptyList()

        val masterHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Host", masterUrl.toHttpUrl().host)
            .add("Origin", "https://${url.toHttpUrl().host}")
            .add("Referer", "https://${url.toHttpUrl().host}/")
            .build()
        val masterPlaylist = client.newCall(
            GET(masterUrl, headers = masterHeaders),
        ).execute().body.string()

        val masterBase = "https://${masterUrl.toHttpUrl().host}${masterUrl.toHttpUrl().encodedPath}"
            .substringBeforeLast("/") + "/"

        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
            .forEach {
                val quality = "Sendvid:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                val videoUrl = masterBase + it.substringAfter("\n").substringBefore("\n")

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", videoUrl.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                videoList.add(Video(videoUrl, prefix + quality, videoUrl, headers = videoHeaders))
            }
        return videoList
    }
}

package eu.kanade.tachiyomi.animeextension.en.animekhor.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val doc = client.newCall(GET(url, headers = headers)).execute().asJsoup()
        val jsEval = doc.selectFirst("script:containsData(m3u8)")?.data() ?: "UwU"

        val masterUrl = JsUnpacker.unpackAndCombine(jsEval)
            ?.substringAfter("source")
            ?.substringAfter("file:\"")
            ?.substringBefore("\"")
            ?: return emptyList()

        val playlistHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Host", masterUrl.toHttpUrl().host)
            .add("Origin", "https://${url.toHttpUrl().host}")
            .set("Referer", "https://${url.toHttpUrl().host}/")
            .build()

        val masterBase = "https://${masterUrl.toHttpUrl().host}${masterUrl.toHttpUrl().encodedPath}"
            .substringBeforeLast("/") + "/"

        val masterPlaylist = client.newCall(
            GET(masterUrl, headers = playlistHeaders),
        ).execute().body.string()
        val separator = "#EXT-X-STREAM-INF:"
        masterPlaylist.substringAfter(separator).split(separator).forEach {
            val quality = prefix + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
            val videoUrl = masterBase + it.substringAfter("\n").substringBefore("\n")
            videoList.add(Video(videoUrl, quality, videoUrl, headers = playlistHeaders))
        }

        return videoList
    }
}

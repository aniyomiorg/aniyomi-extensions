package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VtubeExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, baseUrl: String, prefix: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val docHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .add("Referer", "$baseUrl/")
            .build()
        val doc = client.newCall(GET(url, headers = docHeaders)).execute().asJsoup()

        val jsEval = doc.selectFirst("script:containsData(m3u8)")!!.data()

        val masterUrl = JsUnpacker.unpackAndCombine(jsEval)
            ?.substringAfter("source")
            ?.substringAfter("file:\"")
            ?.substringBefore("\"")
            ?: return emptyList()

        val playlistHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Host", masterUrl.toHttpUrl().host)
            .add("Origin", "https://${url.toHttpUrl().host}")
            .add("Referer", "https://${url.toHttpUrl().host}/")
            .build()

        val masterPlaylist = client.newCall(
            GET(masterUrl, headers = playlistHeaders),
        ).execute().body.string()
        val separator = "#EXT-X-STREAM-INF:"
        masterPlaylist.substringAfter(separator).split(separator).forEach {
            val quality = prefix + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            videoList.add(Video(videoUrl, quality, videoUrl, headers = playlistHeaders))
        }

        return videoList
    }
}

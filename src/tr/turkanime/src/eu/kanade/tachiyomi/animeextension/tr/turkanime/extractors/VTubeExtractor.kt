package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VTubeExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, baseUrl: String, prefix: String = ""): List<Video> {
        val documentHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .add("Referer", "$baseUrl/")
            .build()
        val document = client.newCall(
            GET(url, headers = documentHeaders),
        ).execute().asJsoup()

        val masterUrl = document.selectFirst("script:containsData(sources)")?.let {
            it.data().substringAfter("{file:\"").substringBefore("\"")
        } ?: return emptyList()
        val masterHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Host", masterUrl.toHttpUrl().host)
            .add("Origin", "https://${url.toHttpUrl().host}")
            .add("Referer", "https://${url.toHttpUrl().host}/")
            .build()
        val masterPlaylist = client.newCall(
            GET(masterUrl, headers = masterHeaders),
        ).execute().body.string()
        val videoList = mutableListOf<Video>()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
            .forEach {
                val quality = "VTube:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                val videoUrl = it.substringAfter("\n").substringBefore("\n")

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

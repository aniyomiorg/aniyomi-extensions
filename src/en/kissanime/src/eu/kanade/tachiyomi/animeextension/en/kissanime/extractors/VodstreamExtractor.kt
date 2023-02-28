package eu.kanade.tachiyomi.animeextension.en.kissanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class VodstreamExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun getVideosFromUrl(url: String, referer: String, prefix: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val getIframeHeaders = Headers.headersOf(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Host",
            url.toHttpUrl().host,
            "Referer",
            referer,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
        )
        val iframe = client.newCall(
            GET(url, headers = getIframeHeaders),
        ).execute().asJsoup()

        val sourcesData = iframe.selectFirst("script:containsData(playerInstance)")!!.data()
        val sources = json.decodeFromString<List<Source>>("[${sourcesData.substringAfter("sources: [").substringBefore("],")}]")

        sources.forEach { source ->

            val videoHeaders = Headers.headersOf(
                "Accept", "*/*",
                "Host", source.file.toHttpUrl().host,
                "Origin", "https://${url.toHttpUrl().host}",
                "Referer", "https://${url.toHttpUrl().host}/",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            )

            if (source.file.contains(".m3u8")) {
                val masterPlaylist = client.newCall(GET(source.file, headers = videoHeaders)).execute().body.string()

                val separator = "#EXT-X-STREAM-INF"
                masterPlaylist.substringAfter(separator).split(separator).map {
                    val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                    var videoUrl = it.substringAfter("\n").substringBefore("\n")

                    videoList.add(Video(videoUrl, prefix + quality, videoUrl, headers = videoHeaders))
                }
            } else {
                videoList.add(
                    Video(
                        source.file,
                        prefix + source.label,
                        source.file,
                        headers = videoHeaders,
                    ),
                )
            }
        }

        return videoList
    }

    @Serializable
    data class Source(
        val file: String,
        val label: String,
    )
}

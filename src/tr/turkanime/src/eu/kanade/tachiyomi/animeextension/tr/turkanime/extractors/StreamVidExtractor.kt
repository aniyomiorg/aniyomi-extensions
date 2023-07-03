package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class StreamVidExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()

        val packed = client.newCall(GET(url)).execute()
            .asJsoup().selectFirst("script:containsData(m3u8)")?.data() ?: return emptyList()
        val unpacked = JsUnpacker.unpackAndCombine(packed) ?: return emptyList()
        val masterUrl = Regex("""src: ?"(.*?)"""").find(unpacked)?.groupValues?.get(1) ?: return emptyList()

        val masterHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Host", masterUrl.toHttpUrl().host)
            .add("Origin", "https://${url.toHttpUrl().host}")
            .add("Referer", "https://${url.toHttpUrl().host}/")
            .build()
        val masterPlaylist = client.newCall(
            GET(masterUrl, headers = masterHeaders),
        ).execute().body.string()

        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
            .forEach {
                val quality = "StreamVid:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
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

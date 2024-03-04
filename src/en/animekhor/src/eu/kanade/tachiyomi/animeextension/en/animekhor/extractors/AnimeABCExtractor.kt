package eu.kanade.tachiyomi.animeextension.en.animekhor.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class AnimeABCExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = client.newCall(GET(url, headers = headers)).execute().asJsoup()
        val data = document.selectFirst("script:containsData(m3u8)")?.data() ?: return emptyList()
        val sources = json.decodeFromString<List<Source>>(
            "[${data.substringAfter("sources:")
                .substringAfter("[")
                .substringBefore("]")}]",
        )
        sources.forEach { src ->
            val masterplHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Connection", "keep-alive")
                .add("Host", url.toHttpUrl().host)
                .add("Referer", url)
                .build()
            val masterPlaylist = client.newCall(
                GET(src.file.replace("^//".toRegex(), "https://"), headers = masterplHeaders),
            ).execute().body.string()

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = prefix + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p ${src.label ?: ""}"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                videoList.add(Video(videoUrl, quality, videoUrl, headers = masterplHeaders))
            }
        }

        return videoList
    }

    @Serializable
    data class Source(
        val file: String,
        val label: String? = null,
    )
}

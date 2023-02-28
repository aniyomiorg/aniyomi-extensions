package eu.kanade.tachiyomi.animeextension.id.neonime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

@Serializable
data class VideoConfig(
    val streams: List<Stream>,
) {
    @Serializable
    data class Stream(
        val play_url: String,
        val format_id: Int,
    )
}

class BloggerExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, name: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()

        val jsElement = document.selectFirst("script:containsData(VIDEO_CONFIG)") ?: return emptyList()
        val js = jsElement.data()
        val json = json.decodeFromString<VideoConfig>(js.substringAfter("var VIDEO_CONFIG = "))

        return json.streams.map {
            Video(it.play_url, "${it.format_id} - $name", it.play_url)
        }
    }
}

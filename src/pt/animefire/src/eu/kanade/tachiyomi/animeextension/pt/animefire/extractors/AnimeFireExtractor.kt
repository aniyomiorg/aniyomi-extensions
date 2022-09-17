package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFResponseDto
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
class AnimeFireExtractor(private val client: OkHttpClient, private val json: Json) {

    fun videoListFromElement(videoElement: Element): List<Video> {
        val jsonUrl = videoElement.attr("data-video-src")
        val response = client.newCall(GET(jsonUrl)).execute()
        val responseDto = json.decodeFromString<AFResponseDto>(
            response.body?.string().orEmpty()
        )
        val videoList = responseDto.videos.map {
            val url = it.url.replace("\\", "")
            Video(url, it.quality, url)
        }
        return videoList
    }
}

package eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class AnimesOnlinePlayerExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        return client.newCall(GET(url)).execute()
            .body.string()
            .substringAfter("sources: [")
            .substringBefore("]")
            .split("{")
            .drop(1)
            .map {
                val label = it.substringAfter("label").substringAfter(":\"").substringBefore('"')
                val videoUrl = it.substringAfter("file")
                    .substringAfter(":\"")
                    .substringBefore('"')
                    .replace("\\", "")
                Video(videoUrl, "Player - $label", videoUrl)
            }
    }
}

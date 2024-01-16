package eu.kanade.tachiyomi.lib.sibnetextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class SibnetExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()

        val document = client.newCall(
            GET(url),
        ).execute().asJsoup()
        val script = document.selectFirst("script:containsData(player.src)")?.data() ?: return emptyList()
        val slug = script.substringAfter("player.src").substringAfter("src:")
            .substringAfter("\"").substringBefore("\"")

        val videoUrl = if (slug.contains("http")) {
            slug
        } else {
            "https://${url.toHttpUrl().host}$slug"
        }

        val videoHeaders = Headers.headersOf(
            "Referer",
            url,
        )

        videoList.add(
            Video(videoUrl, "${prefix}Sibnet", videoUrl, headers = videoHeaders),
        )

        return videoList
    }
}

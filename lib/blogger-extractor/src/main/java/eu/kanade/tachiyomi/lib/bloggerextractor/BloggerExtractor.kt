package eu.kanade.tachiyomi.lib.bloggerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class BloggerExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, suffix: String = ""): List<Video> {
        return client.newCall(GET(url, headers)).execute()
            .body.string()
            .takeIf { !it.contains("errorContainer") }
            .let { it ?: return emptyList() }
            .substringAfter("\"streams\":[")
            .substringBefore("]")
            .split("},")
            .mapNotNull {
                val videoUrl = it.substringAfter("\"play_url\":\"").substringBefore('"')
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val format = it.substringAfter("\"format_id\":").substringBefore('}')
                val quality = when (format) {
                    "7" -> "240p"
                    "18" -> "360p"
                    "22" -> "720p"
                    "37" -> "1080p"
                    else -> "Unknown"
                }
                Video(videoUrl, "Blogger - $quality $suffix".trimEnd(), videoUrl, headers)
            }
    }
}

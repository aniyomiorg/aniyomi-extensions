package eu.kanade.tachiyomi.animeextension.de.aniflix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamlareExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): Video? {
        val id = url.split("/").last()
        val referer = client.newCall(
            POST(
                "https://slwatch.co/api/video/stream/get",
                body = "{\"id\":\"$id\"}"
                    .toRequestBody("application/json".toMediaType())
            )
        )
            .execute().asJsoup().toString()
        val token = referer.substringAfter("https:\\/\\/larecontent.com\\/video?token=")
            .substringBefore("\",")
        val videoUrl = "https://larecontent.com/video?token=$token"
        return Video(url, quality, videoUrl, null)
    }
}

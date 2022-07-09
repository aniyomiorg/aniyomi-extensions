package eu.kanade.tachiyomi.animeextension.de.aniflix.extractors

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.Stream
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamlareExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, stream: Stream, resPreference: String?): Video? {
        val id = url.split("/").last()
        val referer = client.newCall(
            POST(
                "https://slwatch.co/api/video/stream/get",
                body = "{\"id\":\"$id\"}"
                    .toRequestBody("application/json".toMediaType())
            )
        )
            .execute().asJsoup().toString()

        val token =
            when {
                referer.contains("1080p") && resPreference?.contains("1080") == true ->
                    referer.substringAfter("\"label\":\"1080p\",\"file\":\"https:\\/\\/larecontent.com\\/video?token=")
                        .substringBefore("\",")
                referer.contains("720p") && resPreference?.contains("720") == true ->
                    referer.substringAfter("\"label\":\"720p\",\"file\":\"https:\\/\\/larecontent.com\\/video?token=")
                        .substringBefore("\",")
                referer.contains("480p") && resPreference?.contains("480") == true ->
                    referer.substringAfter("\"label\":\"480p\",\"file\":\"https:\\/\\/larecontent.com\\/video?token=")
                        .substringBefore("\",")
                referer.contains("360p") && resPreference?.contains("360") == true ->
                    referer.substringAfter("\"label\":\"360p\",\"file\":\"https:\\/\\/larecontent.com\\/video?token=")
                        .substringBefore("\",")

                else ->
                    referer.substringAfter("https:\\/\\/larecontent.com\\/video?token=")
                        .substringBefore("\",")
            }

        val quality =
            when {
                referer.contains("1080p") && resPreference?.contains("1080") == true -> {
                    "${stream.hoster?.name}, 1080p, ${stream.lang}"
                }
                referer.contains("720p") && resPreference?.contains("720") == true -> {
                    "${stream.hoster?.name}, 720p, ${stream.lang}"
                }
                referer.contains("480p") && resPreference?.contains("480") == true -> {
                    "${stream.hoster?.name}, 480p, ${stream.lang}"
                }
                referer.contains("360p") && resPreference?.contains("360") == true -> {
                    "${stream.hoster?.name}, 360p, ${stream.lang}"
                }
                else ->
                    "${stream.hoster?.name}, Unknown, ${stream.lang}"
            }

        val videoUrl = "https://larecontent.com/video?token=$token"
        return Video(url, quality, videoUrl, null)
    }
}

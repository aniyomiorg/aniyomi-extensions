package eu.kanade.tachiyomi.animeextension.de.aniflix.extractors

import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.Stream
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamlareExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, stream: Stream, preferences: SharedPreferences): Video? {
        val id = url.split("/").last()
        val referer = client.newCall(
            POST(
                "https://slwatch.co/api/video/stream/get",
                body = "{\"id\":\"$id\"}"
                    .toRequestBody("application/json".toMediaType())
            )
        )
            .execute().asJsoup().toString()

        val resPreference = preferences.getString("preferred_res", "1080")
        val token =
            when {
                referer.contains("$resPreference" + "p") && resPreference?.contains("$resPreference") == true ->
                    referer.substringAfter("\"label\":\"$resPreference" + "p\",\"file\":\"https:\\/\\/larecontent.com\\/video?token=")
                        .substringBefore("\",")

                else ->
                    referer.substringAfter("https:\\/\\/larecontent.com\\/video?token=")
                        .substringBefore("\",")
            }

        val quality =
            when {
                referer.contains("$resPreference" + "p") && resPreference?.contains("$resPreference") == true -> {
                    "${stream.hoster?.name}, $resPreference" + "p, ${stream.lang}"
                }
                else -> {
                    "${stream.hoster?.name} Unknown, ${stream.lang}"
                }
            }

        val videoUrl = "https://larecontent.com/video?token=$token"
        return Video(url, quality, videoUrl, null)
    }
}

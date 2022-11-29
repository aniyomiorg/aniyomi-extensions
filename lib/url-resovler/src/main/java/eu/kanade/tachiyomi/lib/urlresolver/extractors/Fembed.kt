package eu.kanade.tachiyomi.lib.urlresolver.extractors


import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class Fembed(private val client: OkHttpClient) {
    fun extract(url: String, prefix: String = ""): List<Video> {
        val videoApi = url.replace("/v/", "/api/source/")
        val body = runCatching {
            client.newCall(POST(videoApi)).execute().body?.string().orEmpty()
        }.getOrNull() ?: return emptyList<Video>()

        val jsonResponse = Json { ignoreUnknownKeys = true }
            .decodeFromString<FembedResponse>(body)

        return if (jsonResponse.success) {
            jsonResponse.data.map {
                val quality = ("Fembed:${it.label}").let {
                    if (prefix.isNotBlank()) "$prefix $it"
                    else it
                }
                Video(it.file, quality, it.file)
            }
        } else { emptyList<Video>() }
    }
    @Serializable
    data class FembedResponse(
        val success: Boolean,
        val data: List<FembedVideo> = emptyList()
    )

    @Serializable
    data class FembedVideo(
        val file: String,
        val label: String
    )
}


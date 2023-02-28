package eu.kanade.tachiyomi.animeextension.all.animexin.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Serializable
data class FembedResponse(
    val success: Boolean,
    val data: List<FembedVideo> = emptyList(),
    val captions: List<Caption> = emptyList(),
) {
    @Serializable
    data class FembedVideo(
        val file: String,
        val label: String,
    )

    @Serializable
    data class Caption(
        val id: String,
        val hash: String,
        val language: String,
        val extension: String,
    )
}

class FembedExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = "", redirect: Boolean = false): List<Video> {
        val videoApi = if (redirect) {
            (
                runCatching {
                    client.newCall(GET(url)).execute().request.url.toString()
                        .replace("/v/", "/api/source/")
                }.getOrNull() ?: return emptyList<Video>()
                )
        } else {
            url.replace("/v/", "/api/source/")
        }
        val body = runCatching {
            client.newCall(POST(videoApi)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        val userId = client.newCall(GET(url)).execute().asJsoup()
            .selectFirst("script:containsData(USER_ID)")!!
            .data()
            .substringAfter("USER_ID")
            .substringAfter("'")
            .substringBefore("'")

        val jsonResponse = try { Json { ignoreUnknownKeys = true }.decodeFromString<FembedResponse>(body) } catch (e: Exception) { FembedResponse(false, emptyList(), emptyList()) }

        return if (jsonResponse.success) {
            val subtitleList = mutableListOf<Track>()
            try {
                subtitleList.addAll(
                    jsonResponse.captions.map {
                        Track(
                            "https://${url.toHttpUrl().host}/asset/userdata/$userId/caption/${it.hash}/${it.id}.${it.extension}",
                            it.language,
                        )
                    },
                )
            } catch (a: Exception) { }

            jsonResponse.data.map {
                val quality = ("Fembed:${it.label}").let {
                    if (prefix.isNotBlank()) {
                        "$prefix $it"
                    } else {
                        it
                    }
                }
                try {
                    Video(it.file, quality, it.file, subtitleTracks = subtitleList)
                } catch (a: Exception) {
                    Video(it.file, quality, it.file)
                }
            }
        } else { emptyList<Video>() }
    }
}

package eu.kanade.tachiyomi.lib.fembedextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class FembedExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = "", redirect: Boolean = false): List<Video> {
        val videoApi = if (redirect) {
            (runCatching { client.newCall(GET(url)).execute().request.url.toString()
                .replace("/v/", "/api/source/") }.getOrNull() ?: return emptyList<Video>())
        } else {
            url.replace("/v/", "/api/source/")
        }
        val body = runCatching {
            client.newCall(POST(videoApi)).execute().body?.string().orEmpty()
        }.getOrNull() ?: return emptyList<Video>()

        val jsonResponse = try{ Json { ignoreUnknownKeys = true }.decodeFromString<FembedResponse>(body) } catch (e: Exception) { FembedResponse(false, emptyList()) }

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
}

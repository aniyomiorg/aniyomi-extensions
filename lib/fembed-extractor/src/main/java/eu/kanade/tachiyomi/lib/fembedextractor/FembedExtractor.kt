package eu.kanade.tachiyomi.lib.fembedextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class FembedExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()
    fun videosFromUrl(url: String, prefix: String = "", redirect: Boolean = false): List<Video> {
        val videoApi = when {
            redirect -> runCatching {
                client.newCall(GET(url)).execute().request.url.toString()
            }.getOrNull() ?: return emptyList<Video>()
            else -> url
        }.replace("/v/", "/api/source/")

        val jsonResponse = runCatching {
            client.newCall(POST(videoApi)).execute().use {
                json.decodeFromString<FembedResponse>(it.body.string())
            }
        }.getOrNull() ?: return emptyList<Video>()

        if (!jsonResponse.success) return emptyList<Video>()

        return jsonResponse.data.map {
            val quality = ("Fembed:${it.label}").let {
                if (prefix.isNotBlank()) {
                    "$prefix $it"
                } else {
                    it
                }
            }
            Video(it.file, quality, it.file)
        }
    }
}

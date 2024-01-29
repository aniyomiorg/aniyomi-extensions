package eu.kanade.tachiyomi.animeextension.pt.betteranime.extractors

import eu.kanade.tachiyomi.animeextension.pt.betteranime.dto.ChangePlayerDto
import eu.kanade.tachiyomi.animeextension.pt.betteranime.unescape
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.parallelMapNotNullBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class BetterAnimeExtractor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json,
) {

    private val headers = Headers.headersOf("Referer", baseUrl)

    fun videoListFromHtml(html: String): List<Video> {
        val qualities = REGEX_QUALITIES.findAll(html).map {
            Pair(it.groupValues[1], it.groupValues[2])
        }.toList()
        val token = html.substringAfter("_token:\"").substringBefore("\"")
        return qualities.parallelMapNotNullBlocking { (quality, qtoken) ->
            videoUrlFromToken(qtoken, token)?.let { videoUrl ->
                Video(videoUrl, quality, videoUrl)
            }
        }
    }

    private suspend fun videoUrlFromToken(qtoken: String, token: String): String? {
        val body = """
            {
                "_token": "$token",
                "info": "$qtoken"
            }
        """.trimIndent()
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val request = POST("$baseUrl/changePlayer", headers, reqBody)
        return runCatching {
            val response = client.newCall(request).await().body.string()
            val resJson = json.decodeFromString<ChangePlayerDto>(response)
            videoUrlFromPlayer(resJson.frameLink!!)
        }.getOrNull()
    }

    private suspend fun videoUrlFromPlayer(url: String): String {
        val html = client.newCall(GET(url, headers)).await().body.string()

        val videoUrl = html.substringAfter("file\":")
            .substringAfter("\"")
            .substringBefore("\"")
            .unescape()

        return videoUrl
    }

    companion object {
        private val REGEX_QUALITIES = """qualityString\["(\w+)"\] = "(\S+)"""".toRegex()
    }
}

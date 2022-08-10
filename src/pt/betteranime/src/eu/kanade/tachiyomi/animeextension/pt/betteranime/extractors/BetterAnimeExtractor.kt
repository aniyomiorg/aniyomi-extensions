package eu.kanade.tachiyomi.animeextension.pt.betteranime.extractors

import eu.kanade.tachiyomi.animeextension.pt.betteranime.dto.ChangePlayerDto
import eu.kanade.tachiyomi.animeextension.pt.betteranime.unescape
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class BetterAnimeExtractor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json
) {

    private val headers = Headers.headersOf("Referer", baseUrl)

    fun videoListFromHtml(html: String): List<Video> {
        val qualities = REGEX_QUALITIES.findAll(html).map {
            Pair(it.groupValues[1], it.groupValues[2])
        }
        val token = html.substringAfter("_token:\"").substringBefore("\"")
        return qualities.mapNotNull {
            val videoUrl = videoUrlFromToken(it.second, token)
            if (videoUrl == null)
                null
            else
                Video(videoUrl, it.first, videoUrl)
        }.toList()
    }

    private fun videoUrlFromToken(qtoken: String, _token: String): String? {
        val body = """
            {
                "_token": "$_token",
                "info": "$qtoken"
            }
        """.trimIndent()
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val request = POST("$baseUrl/changePlayer", headers, reqBody)
        val response = client.newCall(request).execute()
        val resJson = json.decodeFromString<ChangePlayerDto>(response.body?.string().orEmpty())
        return videoUrlFromPlayer(resJson.frameLink)
    }

    private fun videoUrlFromPlayer(url: String?): String? {
        if (url == null)
            return null

        val html = client.newCall(GET(url, headers)).execute().body
            ?.string().orEmpty()

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

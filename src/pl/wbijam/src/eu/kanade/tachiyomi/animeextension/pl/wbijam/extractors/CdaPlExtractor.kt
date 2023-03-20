package eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class CdaPlExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun getVideosFromUrl(url: String, headers: Headers): List<Video> {
        val videoList = mutableListOf<Video>()

        val embedHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .build()

        val document = client.newCall(
            GET(url, headers = embedHeaders),
        ).execute().asJsoup()

        val data = json.decodeFromString<PlayerData>(
            document.selectFirst("div[player_data]")!!.attr("player_data"),
        )

        data.video.qualities.forEach { quality ->
            if (quality.value == data.video.quality) {
                val videoUrl = decryptFile(data.video.file)
                videoList.add(
                    Video(videoUrl, "cda.pl - ${quality.key}", videoUrl),
                )
            } else {
                val jsonBody = """
                    {
                        "jsonrpc": "2.0",
                        "method": "videoGetLink",
                        "id": 1,
                        "params": [
                            "${data.video.id}",
                            "${quality.value}",
                            ${data.video.ts},
                            "${data.video.hash2}",
                            {}
                        ]
                    }
                """.trimIndent().toRequestBody("application/json".toMediaType())
                val postHeaders = Headers.headersOf(
                    "Content-Type",
                    "application/json",
                    "X-Requested-With",
                    "XMLHttpRequest",
                )
                val response = client.newCall(
                    POST("https://www.cda.pl/", headers = postHeaders, body = jsonBody),
                ).execute()
                val parsed = json.decodeFromString<PostResponse>(
                    response.body.string(),
                )
                videoList.add(
                    Video(parsed.result.resp, "cda.pl - ${quality.key}", parsed.result.resp),
                )
            }
        }

        return videoList
    }

    // Credit: https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/cda.py
    private fun decryptFile(a: String): String {
        var decrypted = a
        listOf("_XDDD", "_CDA", "_ADC", "_CXD", "_QWE", "_Q5", "_IKSDE").forEach { p ->
            decrypted = decrypted.replace(p, "")
        }
        decrypted = URLDecoder.decode(decrypted, StandardCharsets.UTF_8.toString())
        val b = mutableListOf<Char>()
        decrypted.forEach { c ->
            val f = c.code
            b.add(if (f in 33..126) (33 + (f + 14) % 94).toChar() else c)
        }
        decrypted = b.joinToString("")
        decrypted = decrypted.replace(".cda.mp4", "")
        listOf(".2cda.pl", ".3cda.pl").forEach { p ->
            decrypted = decrypted.replace(p, ".cda.pl")
        }
        if ("/upstream" in decrypted) {
            decrypted = decrypted.replace("/upstream", ".mp4/upstream")
            return "https://$decrypted"
        }
        return "https://$decrypted.mp4"
    }

    @Serializable
    data class PlayerData(
        val video: VideoObject,
    ) {
        @Serializable
        data class VideoObject(
            val id: String,
            val file: String,
            val quality: String,
            val qualities: Map<String, String>,
            val ts: Int,
            val hash2: String,
        )
    }

    @Serializable
    data class PostResponse(
        val result: ResultObject,
    ) {
        @Serializable
        data class ResultObject(
            val resp: String,
        )
    }
}

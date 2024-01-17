package eu.kanade.tachiyomi.animeextension.pl.desuonline.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class CDAExtractor(private val client: OkHttpClient, private val headers: Headers, private val referer: String) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, name: String): List<Video> {
        val urlHost = url.toHttpUrl().host

        val docHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Host", urlHost)
            add("Referer", referer)
        }.build()
        val doc = client.newCall(
            GET(url, headers = docHeaders),
        ).execute().asJsoup()

        val playerData = doc.selectFirst("div[id~=mediaplayer][player_data]")
            ?.attr("player_data")
            ?.let { json.decodeFromString<PlayerData>(it) }
            ?: return emptyList()

        val timestamp = playerData.api.ts.substringBefore("_")
        val videoData = playerData.video

        var idCounter = 1
        return videoData.qualities.map { (quality, qualityId) ->
            val postBody = json.encodeToString(
                buildJsonObject {
                    put("id", idCounter)
                    put("jsonrpc", "2.0")
                    put("method", "videoGetLink")
                    putJsonArray("params") {
                        add(url.toHttpUrl().pathSegments.last())
                        add(qualityId)
                        add(timestamp.toInt())
                        add(videoData.hash2)
                    }
                },
            ).toRequestBody("application/json; charset=utf-8".toMediaType())

            val postHeaders = headers.newBuilder().apply {
                add("Accept", "application/json, text/javascript, */*; q=0.01")
                add("Host", "www.cda.pl")
                add("Origin", "https://$urlHost")
                add("Referer", url)
            }.build()

            val videoUrl = client.newCall(
                POST("https://www.cda.pl/", headers = postHeaders, body = postBody),
            ).execute().parseAs<PostResponse>().result.resp

            idCounter++

            Video(videoUrl, "$name - $quality", videoUrl)
        }
    }

    @Serializable
    data class PlayerData(
        val api: PlayerApi,
        val video: PlayerVideoData,
    ) {
        @Serializable
        data class PlayerApi(
            val ts: String,
        )

        @Serializable
        data class PlayerVideoData(
            val hash2: String,
            val qualities: Map<String, String>,
        )
    }

    @Serializable
    data class PostResponse(
        val result: PostResult,
    ) {
        @Serializable
        data class PostResult(
            val resp: String,
        )
    }
}

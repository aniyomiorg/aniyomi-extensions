package eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class StreamlareExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()
    fun videosFromUrl(url: String): Video? {
        val id = url.substringAfter("/e/").substringBefore("?poster")
        val videoUrlResponse =
            client.newCall(POST("https://slwatch.co/api/video/stream/get?id=$id")).execute()
                .asJsoup()
        json.decodeFromString<JsonObject>(
            videoUrlResponse.select("body").text()
        )["result"]?.jsonObject?.forEach { quality ->
            if (quality.toString().contains("file=\"")) {
                val videoUrl = quality.toString().substringAfter("file=\"").substringBefore("\"").trim()
                val type = if (videoUrl.contains(".m3u8")) "HSL" else "MP4"
                val headers = Headers.Builder()
                    .add("authority", videoUrl.substringBefore("/hls").substringBefore("/mp4"))
                    .add("origin", "https://slwatch.co")
                    .add("referer", "https://slwatch.co/e/" + url.substringAfter("/e/"))
                    .add(
                        "sec-ch-ua",
                        "\"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"108\", \"Google Chrome\";v=\"108\""
                    )
                    .add("sec-ch-ua-mobile", "?0")
                    .add("sec-ch-ua-platform", "\"Windows\"")
                    .add("sec-fetch-dest", "empty")
                    .add("sec-fetch-mode", "cors")
                    .add("sec-fetch-site", "cross-site")
                    .add(
                        "user-agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/),108.0.0.0 Safari/537.36"
                    )
                    .add("Accept-Encoding", "gzip, deflate, br")
                    .add("accept", "*/*")
                    .add(
                        "accept-language",
                        "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7,zh-TW;q=0.6,zh-CN;q=0.5,zh;q=0.4"
                    )
                    .build()
                return Video(videoUrl, "Streamlare:$type", videoUrl, headers = headers)
            }
        }
        return null
    }
}

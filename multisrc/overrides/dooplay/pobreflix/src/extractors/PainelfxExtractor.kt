package eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class PainelfxExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val lang = when (url.substringAfterLast("/")) {
            "leg" -> "Legendado"
            else -> "Dublado"
        }
        return doc.select("div.panel-body > button").flatMap { elem ->
            val form = FormBody.Builder()
                .add("idS", elem.attr("idS"))
                .build()
            val host = url.toHttpUrl().host
            val newHeaders = headers.newBuilder().set("Referer", "https://$host/").build()
            val req = client.newCall(POST("https://$host/CallEpi", body = form)).execute()
            val decoded = req.body.string().decodeHex().let(::String).replace("\\", "")
            if (decoded.contains("video\"")) {
                decoded.substringAfter("video").split("{").drop(1).map {
                    val videoUrl = it.substringAfter("file\":\"").substringBefore('"')
                    val quality = it.substringAfter("label\":\"").substringBefore('"')
                    Video(videoUrl, "$lang - $quality", videoUrl, newHeaders)
                }
            } else {
                emptyList()
            }
        }
    }

    // Stolen from AnimixPlay(EN) / GogoCdnExtractor
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}

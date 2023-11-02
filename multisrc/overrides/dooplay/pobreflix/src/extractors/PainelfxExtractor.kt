package eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class PainelfxExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val genericExtractor: (String, String) -> List<Video>,
) {
    fun videosFromUrl(url: String): List<Video> {
        val docHeaders = headers.newBuilder().set("Referer", "https://gastronomiabrasileira.net/").build()
        val doc = client.newCall(GET(url, docHeaders)).execute().use { it.asJsoup() }
        val lang = when (url.substringAfterLast("/")) {
            "leg" -> "Legendado"
            else -> "Dublado"
        }

        val host = url.toHttpUrl().host
        val videoHeaders = headers.newBuilder().set("Referer", "https://$host/").build()

        val buttons = doc.select("div.panel-body > button")

        val encodedHexList = when {
            buttons.isNotEmpty() -> {
                buttons.map { elem ->
                    val form = FormBody.Builder()
                        .add("idS", elem.attr("idS"))
                        .build()
                    client.newCall(POST("https://$host/CallEpi", body = form)).execute()
                        .use { it.body.string() }
                }
            }
            else -> {
                val script = doc.selectFirst("script:containsData(idS:)")?.data() ?: return emptyList()
                val idList = script.split("idS:").drop(1).map { it.substringAfter('"').substringBefore('"') }

                idList.map { idS ->
                    val form = FormBody.Builder()
                        .add("id", idS)
                        .build()
                    client.newCall(POST("https://$host/CallPlayer", body = form)).execute()
                        .use { it.body.string() }
                }
            }
        }

        return encodedHexList.flatMap {
            videosFromHex(it, lang, videoHeaders)
        }
    }

    private fun videosFromHex(hex: String, lang: String, videoHeaders: Headers): List<Video> {
        val decoded = hex.decodeHex().let(::String).replace("\\", "")
        return if (decoded.contains("video\"")) {
            decoded.substringAfter("video").split("{").drop(1).map {
                val videoUrl = it.substringAfter("file\":\"").substringBefore('"')
                val quality = it.substringAfter("label\":\"").substringBefore('"')
                Video(videoUrl, "$lang - $quality", videoUrl, videoHeaders)
            }
        } else {
            val url = decoded.substringAfter("\"url\":\"").substringBefore('"')
            genericExtractor(url, lang)
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

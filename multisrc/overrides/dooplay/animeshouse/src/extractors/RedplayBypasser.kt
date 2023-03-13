package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

class RedplayBypasser(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    fun fromUrl(url: String): String {
        val next = client.newCall(GET(url, headers)).execute()
            .use { it.asJsoup().selectFirst("a")!!.attr("href") }
        val response = client.newCall(GET(next, headers)).execute()
        return getIframeUrl(response, next)
    }

    private fun getIframeUrl(response: Response, formUrl: String): String {
        return response.use { page ->
            val document = page.asJsoup(decodeAtob(page.body.string()))
            val iframe = document.selectFirst("iframe")
            if (iframe != null) {
                iframe.attr("src")
            } else {
                val newHeaders = headers.newBuilder()
                    .set("Referer", formUrl)
                    .build()

                val formBody = FormBody.Builder().apply {
                    document.select("input[name]").forEach {
                        add(it.attr("name"), it.attr("value"))
                    }
                }.build()

                val nextForm = document.selectFirst("form")!!.attr("action")
                val nextPage = client.newCall(POST(formUrl, newHeaders, formBody))
                    .execute()
                getIframeUrl(nextPage, nextForm)
            }
        }
    }

    private fun decodeAtob(html: String): String {
        val atobContent = html.substringAfter("atob(\"").substringBefore("\"));")
        val hexAtob = atobContent.replace("\\x", "").decodeHex()
        val decoded = Base64.decode(hexAtob, Base64.DEFAULT)
        return String(decoded)
    }

    // Stolen from AnimixPlay(EN) / GogoCdnExtractor
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}

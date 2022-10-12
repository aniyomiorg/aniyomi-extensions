package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class RedplayBypasser(
    private val client: OkHttpClient,
    private val headers: Headers
) {

    fun fromUrl(url: String): String {
        val firstDoc = client.newCall(GET(url, headers)).execute().asJsoup()
        val next = firstDoc.selectFirst("a").attr("href")
        var nextPage = client.newCall(GET(next, headers)).execute()
        var iframeUrl = ""
        var formUrl = next
        while (iframeUrl == "") {
            val nextDoc = nextPage.asJsoup(decodeAtob(nextPage.body!!.string()))
            val iframe = nextDoc.selectFirst("iframe")
            if (iframe != null)
                iframeUrl = iframe.attr("src")
            else {
                val newHeaders = headers.newBuilder()
                    .set("Referer", formUrl)
                    .build()
                val formBody = FormBody.Builder()
                formUrl = nextDoc.selectFirst("form").attr("action")
                nextDoc.select("input[name]").forEach {
                    formBody.add(it.attr("name"), it.attr("value"))
                }
                nextPage = client.newCall(POST(formUrl, newHeaders, formBody.build()))
                    .execute()
            }
        }
        return iframeUrl
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

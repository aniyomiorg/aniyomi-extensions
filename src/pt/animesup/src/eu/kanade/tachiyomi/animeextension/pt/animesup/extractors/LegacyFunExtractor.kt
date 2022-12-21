package eu.kanade.tachiyomi.animeextension.pt.animesup.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class LegacyFunExtractor(private val client: OkHttpClient) {
    private val USER_AGENT = "Mozilla/5.0 (Android 10; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0"

    fun videoFromUrl(url: String, quality: String): Video? {
        var body: Document? = null
        while (true) {
            if (body == null) {
                body = client.newCall(GET(url)).execute().asJsoup()
            } else {
                val form = body.selectFirst("form#link")
                if (form == null) {
                    return getVideoFromDocument(body, quality)
                } else {
                    val url = form.attr("action").let {
                        if (!it.startsWith("http"))
                            "https://legacyfun.site/$it"
                        else it
                    }
                    val token = form.selectFirst("input").attr("value")!!
                    val formBody = FormBody.Builder().apply {
                        add("token", token)
                    }.build()
                    body = client.newCall(POST(url, body = formBody))
                        .execute()
                        .asJsoup()
                }
            }
        }
    }

    private fun getVideoFromDocument(doc: Document, quality: String): Video? {
        val iframeUrl = doc.selectFirst("iframe#iframeidv").attr("src")
        val newHeaders = Headers.headersOf(
            "referer", doc.location(),
            "user-agent", USER_AGENT
        )
        val newDoc = client.newCall(GET(iframeUrl, newHeaders)).execute().asJsoup()
        val body = newDoc.let { doc ->
            doc.selectFirst("script:containsData(eval)")?.let {
                JsUnpacker.unpackAndCombine(it.data())
            } ?: doc.selectFirst("script:containsData(var player)")?.data()
        }
        return body?.let {
            val url = it.substringAfter("file\":")
                .substringAfter("\"")
                .substringBefore("\"")
            val videoHeaders = Headers.headersOf(
                "referer", iframeUrl,
                "user-agent", USER_AGENT
            )
            Video(url, quality, url, videoHeaders)
        }
    }
}

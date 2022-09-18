package eu.kanade.tachiyomi.animeextension.pt.animesonlinex.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class GuiaNoticiarioBypasser(
    private val client: OkHttpClient,
    private val headers: Headers
) {

    private val REGEX_LINK = Regex("link\\.href = \"(\\S+?)\"")

    fun fromUrl(url: String): String {
        val firstBody = client.newCall(GET(url, headers)).execute()
            .body!!.string()

        var next = REGEX_LINK.find(firstBody)!!.groupValues.get(1)
        var currentPage = client.newCall(GET(next, headers)).execute()
        var iframeUrl = ""
        while (iframeUrl == "") {
            val currentBody = currentPage.body!!.string()
            val currentDoc = currentPage.asJsoup(currentBody)
            val possibleIframe = currentDoc.selectFirst("iframe")
            if (REGEX_LINK.containsMatchIn(currentBody)) {
                // Just to get necessary cookies when needed
                if (possibleIframe != null) {
                    client.newCall(GET(possibleIframe.attr("src"), headers)).execute()
                }
                val newHeaders = headers.newBuilder()
                    .set("Referer", next)
                    .build()
                next = REGEX_LINK.find(currentBody)!!.groupValues.get(1)
                currentPage = client.newCall(GET(next, newHeaders)).execute()
            } else {
                iframeUrl = possibleIframe.attr("src")
            }
        }
        return iframeUrl
    }
}

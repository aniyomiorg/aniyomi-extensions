package eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

private const val HOST = "https://sabornutritivo.com"

class ProtectorExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val fixedUrl = if (!url.startsWith("https")) "https:$url" else url
        val token = fixedUrl.toHttpUrl().queryParameter("token")!!
        val headers = Headers.headersOf("cookie", "token=$token;")
        val doc = client.newCall(GET("$HOST/social.php", headers)).execute().asJsoup()
        val videoHeaders = Headers.headersOf("referer", doc.location())
        val iframeUrl = doc.selectFirst("iframe")!!.attr("src").trim()
        return listOf(Video(iframeUrl, "Animes Digital", iframeUrl, videoHeaders))
    }
}

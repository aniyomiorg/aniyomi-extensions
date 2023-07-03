package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class EmbedgramExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val response = client.newCall(GET(url)).execute()
        val xsrfToken = response.headers.firstOrNull {
            it.first == "set-cookie" && it.second.startsWith("XSRF-TOKEN", true)
        }?.second?.substringBefore(";") ?: ""
        val sourceElement = response.asJsoup().selectFirst("video#my-video > source[src~=.]") ?: return emptyList()
        val videoUrl = sourceElement.attr("src").replace("^//".toRegex(), "https://")

        val videoHeaders = headers.newBuilder()
            .add("Cookie", xsrfToken)
            .add("Host", videoUrl.toHttpUrl().host)
            .add("Referer", "https://${url.toHttpUrl().host}/")
            .build()
        return listOf(
            Video(videoUrl, "${prefix}Embedgram", videoUrl, headers = videoHeaders),
        )
    }
}

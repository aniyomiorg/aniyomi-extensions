package eu.kanade.tachiyomi.lib.vudeoextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VudeoExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val doc = client.newCall(GET(url)).execute()
            .asJsoup()

        val sources = doc.selectFirst("script:containsData(sources: [)")?.data()
            ?: return emptyList()

        val referer = "https://" + url.toHttpUrl().host + "/"

        val headers = Headers.headersOf("referer", referer)

        return sources.substringAfter("sources: [").substringBefore("]")
            .replace("\"", "")
            .split(',')
            .filter { it.startsWith("https") } // remove invalid links
            .map { videoUrl ->
                Video(videoUrl, "${prefix}Vudeo", videoUrl, headers)
            }
    }
}

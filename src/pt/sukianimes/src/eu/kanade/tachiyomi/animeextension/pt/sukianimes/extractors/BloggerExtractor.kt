package eu.kanade.tachiyomi.animeextension.pt.sukianimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class BloggerExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, player: String, headers: Headers): List<Video> {
        val iframeBody = client.newCall(GET(url)).execute().asJsoup()
        val iframeUrl = iframeBody.selectFirst("iframe").attr("src")
        val response = client.newCall(GET(iframeUrl, headers)).execute()
        val html = response.body?.string().orEmpty()
        return html.split("play_url").drop(1).map {
            val url = it.substringAfter(":\"").substringBefore("\"")
            val format = it.substringAfter("format_id\":").substringBefore("}")
            val quality = if (format.equals("18")) "SD" else "HD"
            Video(url, "$player - $quality", url, headers = headers)
        }.reversed()
    }
}

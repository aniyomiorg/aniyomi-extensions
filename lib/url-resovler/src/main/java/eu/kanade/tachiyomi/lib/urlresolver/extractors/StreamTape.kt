package eu.kanade.tachiyomi.lib.urlresolver.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamTape(private val client: OkHttpClient) {
    fun extract(url: String, quality: String = "StreamTape"): List<Video> {
        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            // ["https", "", "<domain>", "<???>", "<id>", ...]
            val id = runCatching { url.split("/").get(4) }.getOrNull() ?: return emptyList()
            baseUrl + id
        } else { url }
        val document = client.newCall(GET(newUrl)).execute().asJsoup()
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return emptyList()
        val videoUrl = "https:" + script.substringBefore("'") +
            script.substringAfter("+ ('xcd").substringBefore("'")
        return listOf(Video(videoUrl, quality, videoUrl))
    }
}

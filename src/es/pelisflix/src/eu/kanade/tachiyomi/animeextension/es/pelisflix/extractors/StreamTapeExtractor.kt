package eu.kanade.tachiyomi.animeextension.es.pelisflix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamTapeExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String = "StreamTape"): Video? {
        return try {
            val linkRegex = "https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}/[a-z]/".toRegex()
            val mainUrl = "https://streamtape.com/e/${ url.replace(linkRegex, "") }"
            val document = client.newCall(GET(mainUrl)).execute().asJsoup()
            val script = document.selectFirst("script:containsData(document.getElementById('robotlink'))")
                ?.data()?.substringAfter("document.getElementById('robotlink').innerHTML = '//")
                ?: return null
            val videoUrl = "https://" + script.substringBefore("'+ ('xcd") + script.substringAfter("+ ('xcd").substringBefore("')")
            Video(videoUrl, quality, videoUrl, headers = null)
        } catch (i: Exception) {
            null
        }
    }
}

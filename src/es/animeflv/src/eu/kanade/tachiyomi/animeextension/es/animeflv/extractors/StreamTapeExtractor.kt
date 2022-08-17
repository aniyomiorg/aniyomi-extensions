package eu.kanade.tachiyomi.animeextension.es.animeflv.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamTapeExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String = "StreamTape"): Video? {
        val linkRegex = "https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}/[a-z]/".toRegex()
        val mainUrl = "https://streamtape.com/e/${ url.replace(linkRegex, "") }"
        val document = client.newCall(GET(mainUrl)).execute().asJsoup()
        val script = document.selectFirst("script:containsData(document.getElementById('robotlink'))")
            ?.data()?.substringAfter("document.getElementById('robotlink').innerHTML = '//")
            ?: return null
        val videoUrl = "https://" + script.substringBefore("'+ ('xcd") + script.substringAfter("+ ('xcd").substringBefore("')")
        Log.i("bru url1", videoUrl)
        return Video(videoUrl, quality, videoUrl)
    }
}

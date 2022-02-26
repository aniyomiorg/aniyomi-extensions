package eu.kanade.tachiyomi.animeextension.ar.animerco.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class UQLoadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        Log.i("lol", "$document")
        val check = document.selectFirst("script:containsData(sources)").data()
        val videoUrl = check.substringAfter("sources: [\"").substringBefore("\"")
        Log.i("lill", videoUrl)
        if (check.contains("sources")) {
            return Video(url, quality, videoUrl, null)
        } else {
            return Video(url, "no 1video", "https", null)
        }
    }
}

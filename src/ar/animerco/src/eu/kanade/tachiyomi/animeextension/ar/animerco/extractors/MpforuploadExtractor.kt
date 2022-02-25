package eu.kanade.tachiyomi.animeextension.ar.animerco.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class MpforuploadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        Log.i("lol", "$document")
        val check = document.select("div.error4shared").text()
        val videoUrl = document.select("source").attr("src")
        Log.i("lill", videoUrl)
        if (check.contains("This file is not available any more")) {
            return Video(url, "no 1video", "https", null)
        } else {
            return Video(url, quality, videoUrl, null)
        }
    }
}

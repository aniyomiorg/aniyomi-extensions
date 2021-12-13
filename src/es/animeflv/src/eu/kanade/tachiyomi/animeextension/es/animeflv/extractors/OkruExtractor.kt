package eu.kanade.tachiyomi.animeextension.es.animeflv.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, quality: String): List<Video>? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        Log.i("bruuh", document.select("div[data-options]").attr("data-options"))
        val videoList = mutableListOf<Video>()
        val videosString = document.select("div[data-options]").attr("data-options")
            .substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")
        videosString.split("{\\\"name\\\":\\\"").reversed().forEach {
            val videoUrl = it.substringAfter("url\\\":\\\"")
                .substringBefore("\\\"")
                .replace("\\\\u0026", "&")
            val videoQuality = "Okru: " + it.substringBefore("\\\"")
            if (videoUrl.startsWith("https://")) {
                videoList.add(Video(videoUrl, videoQuality, videoUrl, null))
            }
        }
        return videoList
    }

    private fun getRandomString(length: Int = 10): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun doodHeaders(tld: String) = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://dood.$tld/")
    }.build()
}

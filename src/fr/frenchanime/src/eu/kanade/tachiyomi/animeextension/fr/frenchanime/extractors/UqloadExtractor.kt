package eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, quality: String = "Uqload"): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val check = document.selectFirst("script:containsData(sources)")!!.data()
        val videoUrl = check.substringAfter("sources: [\"").substringBefore("\"")
        val videoHeaders = headers.newBuilder()
            .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            .add("Host", videoUrl.toHttpUrl().host)
            .add("Referer", "https://uqload.co/")
            .build()
        return if (check.contains("sources")) {
            listOf(Video(url, quality, videoUrl, headers = videoHeaders))
        } else {
            emptyList()
        }
    }
}

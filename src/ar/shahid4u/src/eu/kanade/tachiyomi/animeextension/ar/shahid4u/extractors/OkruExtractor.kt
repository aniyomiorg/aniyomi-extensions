package eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import java.lang.Exception

class OkruExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        if (document == null) {
            throw Exception("Not used")
        } else {
            val videoList = mutableListOf<Video>()
            val qualityMap = mapOf(
                "Okru: mobile" to "Okru: 140p",
                "Okru: lowest" to "Okru: 240p",
                "Okru: low" to "Okru: 360p",
                "Okru: sd" to "Okru: 480p",
                "Okru: hd" to "Okru: 720p",
                "Okru: fhd" to "Okru: 1080p"
            )
            val videosString = document.select("div[data-options]").attr("data-options")
                .substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
                .substringBefore("]")
            videosString.split("{\\\"name\\\":\\\"").reversed().forEach { it1 ->
                val videoUrl = it1.substringAfter("url\\\":\\\"")
                    .substringBefore("\\\"")
                    .replace("\\\\u0026", "&")
                val videoQuality = "Okru: " + it1.substringBefore("\\\"")
                if (videoUrl.startsWith("https://")) {
                    val video = qualityMap[videoQuality]?.let { Video(videoUrl, it, videoUrl) }
                    if (video != null)
                        videoList.add(video)
                    else
                        videoList.add(Video(videoUrl, videoQuality, videoUrl))
                }
            }
            return videoList
        }
    }
}

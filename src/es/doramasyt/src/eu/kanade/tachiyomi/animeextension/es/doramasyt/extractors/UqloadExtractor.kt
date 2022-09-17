package eu.kanade.tachiyomi.animeextension.es.doramasyt.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, headers: Headers, quality: String): List<Video> {
        val videoList = mutableListOf<Video>()
        return try {
            val document = client.newCall(GET(url)).execute()
            if (document.isSuccessful) {
                val response = document.asJsoup()
                response.select("script").map {
                    if (it.data().contains("var player =")) {
                        val basicUrl = it.data().substringAfter("sources: [\"").substringBefore("\"],")
                        videoList.add(Video(basicUrl, quality, basicUrl, headers = headers))
                    }
                }
            }
            videoList
        } catch (e: Exception) {
            videoList
        }
    }
}

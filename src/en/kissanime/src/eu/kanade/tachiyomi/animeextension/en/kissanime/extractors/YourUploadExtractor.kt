package eu.kanade.tachiyomi.animeextension.en.kissanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class YourUploadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, headers: Headers, name: String): List<Video> {
        val videoList = mutableListOf<Video>()
        return try {
            val document = client.newCall(GET(url, headers = headers)).execute()
            if (document.isSuccessful) {
                val content = document.asJsoup()
                val baseData =
                    content!!.selectFirst("script:containsData(jwplayerOptions)")!!.data()
                if (!baseData.isNullOrEmpty()) {
                    val basicUrl = baseData.substringAfter("file: '").substringBefore("',")
                    videoList.add(Video(basicUrl, name, basicUrl, headers = headers))
                }
            }
            videoList
        } catch (e: Exception) {
            videoList
        }
    }
}

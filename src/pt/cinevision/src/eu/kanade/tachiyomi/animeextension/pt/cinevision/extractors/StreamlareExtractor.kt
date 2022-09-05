package eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamlareExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, name: String): List<Video> {
        val id = url.substringAfter("/e/").substringBefore("/")
        val body = "{\"id\":\"$id\"}".toRequestBody("application/json".toMediaType())
        val playlist = client.newCall(
            POST("https://sltube.org/api/video/stream/get", body = body)
        ).execute().body!!.string()
        val separator = "\"label\":\""
        return playlist.substringAfter(separator).split(separator).map {
            val quality = "$name - " + it.substringAfter(separator).substringBefore("\",")
            val videoUrl = it.substringAfter("file\":\"").substringBefore("\",")
                .replace("\\", "")

            Video(videoUrl, quality, videoUrl)
        }
    }
}

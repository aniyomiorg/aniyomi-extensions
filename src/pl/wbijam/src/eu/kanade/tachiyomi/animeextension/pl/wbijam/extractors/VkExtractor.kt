package eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VkExtractor(private val client: OkHttpClient) {
    fun getVideosFromUrl(url: String, headers: Headers): List<Video> {
        val videoList = mutableListOf<Video>()

        val documentHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", "vk.com")
            .build()

        val data = client.newCall(
            GET(url, headers = documentHeaders),
        ).execute().body.string()

        val videoRegex = """\"url(\d+)\":\"(.*?)\"""".toRegex()
        videoRegex.findAll(data).forEach {
            val quality = it.groupValues[1]
            val videoUrl = it.groupValues[2].replace("\\/", "/")
            val videoHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Host", videoUrl.toHttpUrl().host)
                .add("Origin", "https://vk.com")
                .add("Referer", "https://vk.com/")
                .build()

            videoList.add(
                Video(videoUrl, "vk.com - ${quality}p", videoUrl, headers = videoHeaders),
            )
        }
        return videoList
    }
}

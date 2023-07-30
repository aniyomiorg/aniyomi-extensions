package eu.kanade.tachiyomi.lib.vkextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VkExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val documentHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", "vk.com")
            .build()

        val data = client.newCall(
            GET(url, headers = documentHeaders),
        ).execute().body.string()

        val videoRegex = """"url(\d+)":"(.*?)"""".toRegex()
        return videoRegex.findAll(data).map {
            val quality = it.groupValues[1]
            val videoUrl = it.groupValues[2].replace("\\/", "/")
            val videoHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Host", videoUrl.toHttpUrl().host)
                .add("Origin", "https://vk.com")
                .add("Referer", "https://vk.com/")
                .build()
            Video(videoUrl, "${prefix}vk.com - ${quality}p", videoUrl, headers = videoHeaders)
        }.toList()
    }
}

package eu.kanade.tachiyomi.lib.vkextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class VkExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val documentHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .build()
    }

    private val videoHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "*/*")
            .add("Origin", VK_URL)
            .add("Referer", "$VK_URL/")
            .build()
    }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val data = client.newCall(GET(url, documentHeaders)).execute()
            .use { it.body.string() }

        return REGEX_VIDEO.findAll(data).map {
            val quality = it.groupValues[1]
            val videoUrl = it.groupValues[2].replace("\\/", "/")
            Video(videoUrl, "${prefix}vk.com - ${quality}p", videoUrl, videoHeaders)
        }.toList()
    }

    companion object {
        private const val VK_URL = "https://vk.com"
        private val REGEX_VIDEO = """"url(\d+)":"(.*?)"""".toRegex()
    }
}

package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VkExtractor(private val client: OkHttpClient) {
    fun getVideosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()

        val documentHeaders = Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", "vk.com")
            .add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36")
            .build()

        val data = client.newCall(
            GET(url, headers = documentHeaders),
        ).execute().body.string()

        val videoRegex = """\"url(\d+)\":\"(.*?)\"""".toRegex()
        videoRegex.findAll(data).forEach {
            val quality = it.groupValues[1]
            val videoUrl = it.groupValues[2].replace("\\/", "/")
            val videoHeaders = Headers.Builder()
                .add("Accept", "*/*")
                .add("Host", videoUrl.toHttpUrl().host)
                .add("Origin", "https://vk.com")
                .add("Referer", "https://vk.com/")
                .add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36")
                .build()

            videoList.add(
                Video(videoUrl, "${prefix}vk.com - ${quality}p", videoUrl, headers = videoHeaders),
            )
        }
        return videoList
    }
}

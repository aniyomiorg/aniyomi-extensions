package eu.kanade.tachiyomi.lib.doodextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true
    ): Video? {
        val newQuality = quality ?: "Doodstream" + if(redirect) " mirror" else ""
        
        return try {
            val response = client.newCall(GET(url)).execute()
            val newUrl = if(redirect) response.request.url.toString() else url
            
            val doodTld = newUrl.substringAfter("https://dood.").substringBefore("/")
            val content = response.body!!.string()
            if (!content.contains("'/pass_md5/")) return null
            val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
            val token = md5.substringAfterLast("/")
            val randomString = getRandomString()
            val expiry = System.currentTimeMillis()
            val videoUrlStart = client.newCall(
                GET(
                    "https://dood.$doodTld/pass_md5/$md5",
                    Headers.headersOf("referer", newUrl)
                )
            ).execute().body!!.string()
            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"
            Video(newUrl, newQuality, videoUrl, headers = doodHeaders(doodTld))
        } catch (e: Exception) {
            null
        }
    }

    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true
    ): List<Video> {
        val video = videoFromUrl(url, quality, redirect)
        return video?.let { listOf(it) } ?: emptyList<Video>()
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

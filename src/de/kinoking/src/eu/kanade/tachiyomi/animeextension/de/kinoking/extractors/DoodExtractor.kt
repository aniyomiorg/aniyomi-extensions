package eu.kanade.tachiyomi.animeextension.de.kinoking.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String): Video? {
        if (url.contains("https://doodstream")) {
            val newurl = client.newCall(GET(url)).execute().request.url.toString()
            val response = client.newCall(GET(newurl)).execute()
            val doodTld = newurl.substringAfter("https://dood.").substringBefore("/")
            val content = response.body!!.string()
            if (!content.contains("'/pass_md5/")) return null
            val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
            val token = md5.substringAfterLast("/")
            val randomString = getRandomString()
            val expiry = System.currentTimeMillis()
            val videoUrlStart = client.newCall(
                GET(
                    "https://dood.$doodTld/pass_md5/$md5",
                    Headers.headersOf("referer", newurl)
                )
            ).execute().body!!.string()
            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"

            return Video(url, quality, videoUrl, headers = doodHeaders(doodTld))
        } else {
            val response = client.newCall(GET(url)).execute()
            val doodTld = url.substringAfter("https://dood.").substringBefore("/")
            val content = response.body!!.string()
            if (!content.contains("'/pass_md5/")) return null
            val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
            val token = md5.substringAfterLast("/")
            val randomString = getRandomString()
            val expiry = System.currentTimeMillis()
            val videoUrlStart = client.newCall(
                GET(
                    "https://dood.$doodTld/pass_md5/$md5",
                    Headers.headersOf("referer", url)
                )
            ).execute().body!!.string()
            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"

            return Video(url, quality, videoUrl, headers = doodHeaders(doodTld))
        }
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

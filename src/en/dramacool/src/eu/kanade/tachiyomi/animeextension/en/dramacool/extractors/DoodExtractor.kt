package eu.kanade.tachiyomi.animeextension.en.dramacool.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String): Video? {
        val response = client.newCall(GET(url)).execute()
        // Overwrite url to the redirected one
        val url = response.request.url.toString()
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
        val newQuality = "Doodstream mirror"

        return Video(url, newQuality, videoUrl, null, doodHeaders(doodTld))
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

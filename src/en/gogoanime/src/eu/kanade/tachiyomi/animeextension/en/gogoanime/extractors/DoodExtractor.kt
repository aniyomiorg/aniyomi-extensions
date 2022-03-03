package eu.kanade.tachiyomi.animeextension.en.gogoanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(serverUrl: String): List<Video> {
        val response = client.newCall(GET(serverUrl)).execute()
        val doodTld = serverUrl.substringAfter("https://dood.").substringBefore("/")
        val content = response.body!!.string()
        if (!content.contains("'/pass_md5/")) return emptyList()
        val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
        val token = md5.substringAfterLast("/")
        val randomString = getRandomString()
        val expiry = System.currentTimeMillis()
        val videoUrlStart = client.newCall(
            GET(
                "https://dood.$doodTld/pass_md5/$md5",
                Headers.headersOf("referer", serverUrl)
            )
        ).execute().body!!.string()
        val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"
        return listOf(Video(serverUrl, "Doodstream mirror", videoUrl, null, doodHeaders(doodTld)))
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

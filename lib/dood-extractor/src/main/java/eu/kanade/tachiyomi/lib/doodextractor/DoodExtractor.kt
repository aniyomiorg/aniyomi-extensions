package eu.kanade.tachiyomi.lib.doodextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
        externalSubs: List<Track> = emptyList(),
    ): Video? {
        val newQuality = quality ?: ("Doodstream" + if (redirect) " mirror" else "")

        return runCatching {
            val response = client.newCall(GET(url)).execute()
            val newUrl = if (redirect) response.request.url.toString() else url

            val doodHost = Regex("https://(.*?)/").find(newUrl)!!.groupValues[1]
            val content = response.body.string()
            if (!content.contains("'/pass_md5/")) return null
            val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
            val token = md5.substringAfterLast("/")
            val randomString = getRandomString()
            val expiry = System.currentTimeMillis()
            val videoUrlStart = client.newCall(
                GET(
                    "https://$doodHost/pass_md5/$md5",
                    Headers.headersOf("referer", newUrl),
                ),
            ).execute().body.string()
            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"
            Video(newUrl, newQuality, videoUrl, headers = doodHeaders(doodHost), subtitleTracks = externalSubs)
        }.getOrNull()
    }

    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
    ): List<Video> {
        val video = videoFromUrl(url, quality, redirect)
        return video?.let(::listOf) ?: emptyList<Video>()
    }

    private fun getRandomString(length: Int = 10): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun doodHeaders(host: String) = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://$host/")
    }.build()
}

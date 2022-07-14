package eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class EvoloadExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val id = url.substringAfter("https://evoload.io/e/")
        val csrv_token =
            client.newCall(GET("https://csrv.evosrv.com/captcha?m412548=")).execute().body!!.string() // whatever that is
        val captchaPass = client.newCall(GET("https://cd2.evosrv.com/html/jsx/e.jsx")).execute().toString()
            .substringAfter("var captcha_pass = '").substringBefore("'")
        val file = client.newCall(
            POST(
                "https://evoload.io/SecurePlayer",
                body = "{\"code\":\"$id\",\"token\":\"ok\",\"csrv_token\":\"$csrv_token\",\"pass\":\"$captchaPass\",\"reff\":\"https://filmpalast.to/\"}".toRequestBody("application/json".toMediaType())
            )
        ).execute().body!!.string()
        val src = file.substringAfter("\"src\":\"").substringBefore("\",")
        val res = client.newCall(POST(src)).execute()
        val videoUrl = res.request.url.toString()
        videoList.addAll(listOf(Video(url, quality, videoUrl, null)))
        return videoList
    }
}

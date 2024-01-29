package eu.kanade.tachiyomi.lib.burstcloudextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class BurstCloudExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videoFromUrl(url: String, headers: Headers, name: String = "BurstCloud", prefix: String = ""): List<Video> {
        val newHeaders = headers.newBuilder().set("referer", BURSTCLOUD_URL).build()
        return runCatching {
            val response = client.newCall(GET(url, newHeaders)).execute()
            val document = response.asJsoup()
            val videoId = document.selectFirst("div#player")!!.attr("data-file-id")

            val formBody = FormBody.Builder()
                .add("fileId", videoId)
                .build()

            val jsonHeaders = headers.newBuilder().set("referer", document.location()).build()
            val request = POST("$BURSTCLOUD_URL/file/play-request/", jsonHeaders, formBody)
            val jsonString = client.newCall(request).execute().body.string()

            val jsonObj = json.decodeFromString<BurstCloudDto>(jsonString)
            val videoUrl = jsonObj.purchase.cdnUrl

            if (videoUrl.isNotEmpty()) {
                val quality = prefix + name
                listOf(Video(videoUrl, quality, videoUrl, newHeaders))
            } else {
                null
            }
        }.getOrNull().orEmpty()
    }
}

private const val BURSTCLOUD_URL = "https://www.burstcloud.co"

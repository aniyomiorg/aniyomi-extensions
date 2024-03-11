package eu.kanade.tachiyomi.animeextension.pt.vizer.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class WarezExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, lang: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val httpUrl = doc.location().toHttpUrl()
        val videoId = httpUrl.queryParameter("id") ?: return emptyList()
        val script = doc.selectFirst("script:containsData(allowanceKey)")?.data()
            ?: return emptyList()
        val key = script.substringAfter("allowanceKey").substringAfter('"').substringBefore('"')
        val cdn = script.substringAfter("cdnListing").substringAfter('[').substringBefore(']')
            .split(',')
            .random()

        val body = FormBody.Builder()
            .add("getVideo", videoId)
            .add("key", key)
            .build()

        val host = "https://" + httpUrl.host
        val reqHeaders = headers.newBuilder()
            .set("Origin", host)
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val req = client.newCall(POST("$host/player/functions.php", reqHeaders, body)).execute()
        val id = req.body.string().substringAfter("id\":\"", "").substringBefore('"', "")
            .ifBlank { return emptyList() }
        val decrypted = decryptorium(id)
        val videoUrl = "https://workerproxy.warezcdn.workers.dev/?url=https://cloclo$cdn.cloud.mail.ru/weblink/view/$decrypted"
        return listOf(Video(videoUrl, "WarezCDN - $lang", videoUrl, headers))
    }

    private fun decryptorium(enc: String): String {
        val b64dec = String(Base64.decode(enc, Base64.DEFAULT)).trim()
        val start = b64dec.reversed().dropLast(5)
        val end = b64dec.substring(0, 5)
        return start + end
    }
}

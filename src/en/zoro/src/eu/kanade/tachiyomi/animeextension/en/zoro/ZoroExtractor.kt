package eu.kanade.tachiyomi.animeextension.en.zoro

import android.net.Uri
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ZoroExtractor(private val client: OkHttpClient) {
    fun getSourcesLink(url: String): String? {
        val html = client.newCall(GET(url, Headers.headersOf("referer", "https://zoro.to/"))).execute().body!!.string()
        val key = html.substringAfter("var recaptchaSiteKey = '", "")
            .substringBefore("',", "").ifEmpty { return null }
        val number = html.substringAfter("recaptchaNumber = '", "")
            .substringBefore("';", "").ifEmpty { return null }
        val captcha = captcha(url, key) ?: return null
        val id = url.substringAfter("/embed-6/", "")
            .substringBefore("?z=", "").ifEmpty { return null }
        val sId = sId() ?: return null
        return "https://rapid-cloud.ru/ajax/embed-6/getSources?id=$id&_token=$captcha&_number=$number&sId=$sId"
    }

    private fun sId(): String? {
        val latch = CountDownLatch(1)
        var sId: String? = null
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("40")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("40")) {
                    sId = text
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }
        }
        client.newWebSocket(GET("wss://ws1.rapid-cloud.ru/socket.io/?EIO=4&transport=websocket"), listener)
        latch.await(30, TimeUnit.SECONDS)
        return sId?.substringAfter("40{\"sid\":\"", "")
            ?.substringBefore("\"", "")
    }

    private fun captcha(url: String, key: String): String? {
        val uri = Uri.parse(url)
        val domain = (Base64.encodeToString((uri.scheme + "://" + uri.host + ":443").encodeToByteArray(), Base64.NO_PADDING) + ".")
            .replace("\n", "")
        val headers = Headers.headersOf("referer", uri.scheme + "://" + uri.host)
        val vToken = client.newCall(GET("https://www.google.com/recaptcha/api.js?render=$key", headers)).execute().body!!.string()
            .replace("\n", "").substringAfter("/releases/", "")
            .substringBefore("/recaptcha", "").ifEmpty { return null }
        if (vToken.isEmpty()) return null
        val anchorUrl = "https://www.google.com/recaptcha/api2/anchor?ar=1&hl=en&size=invisible&cb=kr60249sk&k=$key&co=$domain&v=$vToken"
        val recapToken = client.newCall(GET(anchorUrl)).execute().asJsoup()
            .selectFirst("#recaptcha-token")?.attr("value") ?: return null
        val body = FormBody.Builder()
            .add("v", vToken)
            .add("k", key)
            .add("c", recapToken)
            .add("co", domain)
            .add("sa", "")
            .add("reason", "q")
            .build()
        return client.newCall(POST("https://www.google.com/recaptcha/api2/reload?k=$key", body = body)).execute().body!!.string()
            .replace("\n", "")
            .substringAfter("rresp\",\"", "")
            .substringBefore("\",null", "")
            .ifEmpty { null }
    }
}

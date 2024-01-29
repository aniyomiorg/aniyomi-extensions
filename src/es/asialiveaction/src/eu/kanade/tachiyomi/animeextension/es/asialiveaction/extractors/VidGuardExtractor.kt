package eu.kanade.tachiyomi.animeextension.es.asialiveaction.extractors

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VidGuardExtractor(private val client: OkHttpClient) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class JsObject(private val latch: CountDownLatch) {
        var payload: String = ""

        @JavascriptInterface
        fun passPayload(passedPayload: String) {
            payload = passedPayload
            latch.countDown()
        }
    }

    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val scriptUrl = doc.selectFirst("script[src*=ad/plugin]")
            ?.absUrl("src")
            ?: return emptyList()

        val headers = Headers.headersOf("Referer", url)
        val script = client.newCall(GET(scriptUrl, headers)).execute()
            .body.string()

        val sources = getSourcesFromScript(script, url)
            .takeIf { it.isNotBlank() && it != "undefined" }
            ?: return emptyList()

        return sources.substringAfter("stream:[").substringBefore("}]")
            .split('{')
            .drop(1)
            .mapNotNull { line ->
                val resolution = line.substringAfter("Label\":\"").substringBefore('"')
                val videoUrl = line.substringAfter("URL\":\"").substringBefore('"')
                    .takeIf(String::isNotBlank)
                    ?.let(::fixUrl)
                    ?: return@mapNotNull null
                Video(videoUrl, "VidGuard:$resolution", videoUrl, headers)
            }
    }

    private fun getSourcesFromScript(script: String, url: String): String {
        val latch = CountDownLatch(1)

        var webView: WebView? = null

        val jsinterface = JsObject(latch)

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            webview.addJavascriptInterface(jsinterface, "android")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.clearCache(true)
                    view?.clearFormData()
                    view?.evaluateJavascript(script) {}
                    view?.evaluateJavascript("window.android.passPayload(JSON.stringify(window.svg))") {}
                }
            }

            webview.loadDataWithBaseURL(url, "<html></html>", "text/html", "UTF-8", null)
        }

        latch.await(5, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return jsinterface.payload
    }

    private fun fixUrl(url: String): String {
        val httpUrl = url.toHttpUrl()
        val originalSign = httpUrl.queryParameter("sig")!!
        val newSign = originalSign.chunked(2).joinToString("") {
            Char(it.toInt(16) xor 2).toString()
        }
            .let { String(Base64.decode(it, Base64.DEFAULT)) }
            .substring(5)
            .chunked(2)
            .reversed()
            .joinToString("")
            .substring(5)

        return httpUrl.newBuilder()
            .removeAllQueryParameters("sig")
            .addQueryParameter("sig", newSign)
            .build()
            .toString()
    }
}

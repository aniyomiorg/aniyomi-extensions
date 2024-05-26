package eu.kanade.tachiyomi.animeextension.ar.cimaleek.interceptor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewResolver(private val globalHeaders: Headers) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun getUrl(origRequestUrl: String, origRequestheader: Headers): Result {
        val latch = CountDownLatch(2)
        var webView: WebView? = null
        val result = Result("", "")
        val headers = origRequestheader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = globalHeaders["User-Agent"]
            }
            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if ("vtt" in url) {
                        result.subtitle = url
                        latch.countDown()
                    }
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        result.url = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        return result
    }

    companion object {
        const val TIMEOUT_SEC: Long = 25
        private val VIDEO_REGEX by lazy { Regex("\\.(mp4|m3u8)") }
    }

    data class Result(var url: String, var subtitle: String)
}

package eu.kanade.tachiyomi.animeextension.fr.empirestreaming

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor : Interceptor {

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val newRequest = resolveWithWebView(originalRequest) ?: throw Exception("bruh")

        return chain.proceed(newRequest)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request? {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webView: WebView? = null

        val origRequestUrl = request
        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        var newRequest: Request? = null

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = "Mozilla/5.0 (Linux; Android 12; SM-T870 Build/SP2A.220305.013; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/106.0.5249.126 Safari/537.36"
            }

            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.toString().contains("/build/")) {
                        newRequest = origRequestUrl.newBuilder().headers(request.requestHeaders.toHeaders()).build()
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl.url.toString(), headers)
        }

        // Wait a reasonable amount of time to retrieve the solution. The minimum should be
        // around 4 seconds but it can take more due to slow networks or server issues.
        latch.await(12, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return newRequest
    }
}

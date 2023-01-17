package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JsInterceptor(private val lang: String) : Interceptor {

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class JsObject(private val latch: CountDownLatch, var payload: String = "") {
        @JavascriptInterface
        fun passPayload(passedPayload: String) {
            payload = passedPayload
            latch.countDown()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val newRequest = resolveWithWebView(originalRequest) ?: throw Exception("Please reload Episode List")

        return chain.proceed(newRequest)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request? {

        val latch = CountDownLatch(1)

        var webView: WebView? = null

        val origRequestUrl = request.url.toString()

        val jsinterface = JsObject(latch)

        // JavaSrcipt gets the Dub or Sub link of vidstream
        val jsScript = """
            (function(){
                let hoster = document.querySelector('div[data-type="$lang"] ul li[data-sv-id="41"]');
                let event = document.createEvent('HTMLEvents');
                event.initEvent('click',true,true);
                hoster.dispatchEvent(event);
                let intervalId = setInterval(() => {
                    let element = document.querySelector("#player iframe");
                    if (element) {
                        clearInterval(intervalId);
                        window.android.passPayload(element.src)
                    }
                }, 500);
            })();
        """

        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        var newRequest: Request? = null

        var head = ""

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:106.0) Gecko/20100101 Firefox/106.0"
                webview.addJavascriptInterface(jsinterface, "android")
                webview.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (request?.url.toString().contains("https://vidstream.pro/embed")) {
                            head = request?.url.toString()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(jsScript) {}
                    }
                }
                webView?.loadUrl(origRequestUrl, headers)
            }
        }

        latch.await(12, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        newRequest = GET(
            request.url.toString(),
            headers = Headers.headersOf(
                "url",
                if (jsinterface.payload.isNullOrEmpty() || (!jsinterface.payload.contains("https://vidstream.pro/embed"))) {
                    head
                } else {
                    jsinterface.payload
                }
            )
        )
        return newRequest
    }
}

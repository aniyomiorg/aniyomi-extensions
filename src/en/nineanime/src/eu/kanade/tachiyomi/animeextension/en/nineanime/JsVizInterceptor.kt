package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
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

class JsVizInterceptor(private val embedLink: String) : Interceptor {

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

        // JavaSrcipt creates Iframe on vidstream page to bypass iframe-cors and gets the sourceUrl
        val jsScript = """
            (function(){
                    const html = '<iframe src="$embedLink" allow="autoplay; fullscreen" allowfullscreen="yes" scrolling="no" style="width: 100%; height: 100%; overflow: hidden;" frameborder="no"></iframe>';
                    document.body.innerHTML += html;
                setTimeout(function() {
                    const iframe = document.querySelector('iframe');
                    const entries = iframe.contentWindow.performance.getEntries();
                    entries.forEach((entry) => {
                        if(entry.initiatorType.includes("xmlhttprequest")){
                            if(!entry.name.includes("/ping/") && !entry.name.includes("/assets/")){
                               window.android.passPayload(entry.name);
                            }
                        }
                    });
                }, 2000);
            })();
        """

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
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:106.0) Gecko/20100101 Firefox/106.0"
                webview.addJavascriptInterface(jsinterface, "android")
                webview.webViewClient = object : WebViewClient() {
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
        newRequest = GET(request.url.toString(), headers = Headers.headersOf("url", jsinterface.payload))
        return newRequest
    }
}

package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JsVrfInterceptor(private val baseUrl: String) {

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val vrfWebView = createWebView()

    fun wake() = ""

    fun getVrf(query: String): String {
        val jscript = getJs(query)
        val cdl = CountDownLatch(1)
        var vrf = ""
        handler.post {
            vrfWebView?.evaluateJavascript(jscript) {
                vrf = it?.removeSurrounding("\"") ?: ""
                cdl.countDown()
            }
        }
        cdl.await(12, TimeUnit.SECONDS)
        if (vrf.isBlank()) throw Exception("vrf could not be retrieved")
        return vrf
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView? {
        val latch = CountDownLatch(1)
        var webView: WebView? = null

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

                webview.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (request?.url.toString().contains("$baseUrl/filter")) {
                            return super.shouldOverrideUrlLoading(view, request)
                        } else {
                            // Block the request
                            return true
                        }
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        latch.countDown()
                    }
                }
                webView?.loadUrl("$baseUrl/filter")
            }
        }

        latch.await()

        handler.post {
            webView?.stopLoading()
        }
        return webView
    }

    private fun getJs(query: String): String {
        return """
            (function() {
                  document.querySelector("form.filters input.form-control").value = '$query';
                  let inputElemente = document.querySelector('form.filters input.form-control');
                  let e = document.createEvent('HTMLEvents');
                  e.initEvent('keyup', true, true);
                  inputElemente.dispatchEvent(e);
                  let val = "";
                  while (val == "") {
                    let element = document.querySelector('form.filters input[type="hidden"]').value;
                    if (element) {
                      val = element;
                      break;
                    }
                  }
                  document.querySelector("form.filters input.form-control").value = '';
                  return val;
            })();
        """.trimIndent()
    }
}

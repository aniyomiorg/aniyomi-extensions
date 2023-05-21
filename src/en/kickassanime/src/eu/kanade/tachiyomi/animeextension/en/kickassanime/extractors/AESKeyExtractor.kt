package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AESKeyExtractor(private val client: OkHttpClient) {
    companion object {
        val KEY_MAP = mutableMapOf(
            // Default key. if it changes, the extractor will update it.
            "PinkBird" to "7191d608bd4deb4dc36f656c4bbca1b7".toByteArray(),
            "SapphireDuck" to "f04274d54a9e01ed4a728c5c1889886e".toByteArray(), // i hate sapphire
        )

        private const val ERROR_MSG_GENERIC = "the AES key was not found."
        private const val ERROR_MSG_VAR = "the AES key variable was not found"
        private val KEY_VAR_REGEX by lazy { Regex("\\.AES\\[.*?\\]\\((\\w+)\\),") }
    }

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class ExtractorJSI(private val latch: CountDownLatch, private val prefix: String) {
        @JavascriptInterface
        fun setKey(key: String) {
            AESKeyExtractor.KEY_MAP.set(prefix, key.toByteArray())
            latch.countDown()
        }
    }

    fun getKeyFromHtml(url: String, html: String, prefix: String): ByteArray {
        val patchedScript = patchScriptFromHtml(url, html)
        return getKey(patchedScript, prefix)
    }

    fun getKeyFromUrl(url: String, prefix: String): ByteArray {
        val patchedScript = patchScriptFromUrl(url)
        return getKey(patchedScript, prefix)
    }

    private fun getKey(patchedScript: String, prefix: String): ByteArray {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val jsi = ExtractorJSI(latch, prefix)

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                cacheMode = WebSettings.LOAD_NO_CACHE
                databaseEnabled = false
                domStorageEnabled = false
                javaScriptEnabled = true
                loadWithOverviewMode = false
                useWideViewPort = false

                webview.addJavascriptInterface(jsi, "AESKeyExtractor")
            }

            webView?.loadData("<html><body></body></html>", "text/html", "UTF-8")
            webView?.evaluateJavascript(patchedScript) {}
        }

        latch.await(30, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return AESKeyExtractor.KEY_MAP.get(prefix) ?: throw Exception(ERROR_MSG_GENERIC)
    }

    private fun patchScriptFromUrl(url: String): String {
        return client.newCall(GET(url)).execute()
            .body.string()
            .let {
                patchScriptFromHtml(url.substringBeforeLast("/"), it)
            }
    }

    private fun patchScriptFromHtml(baseUrl: String, body: String): String {
        val scriptPath = body.substringAfter("script src=\"").substringBefore('"')
        val scriptUrl = "$baseUrl/$scriptPath"
        val scriptBody = client.newCall(GET(scriptUrl)).execute().body.string()

        val varWithKeyName = KEY_VAR_REGEX.find(scriptBody)
            ?.groupValues
            ?.last()
            ?: Exception(ERROR_MSG_VAR)

        val varWithKeyBody = scriptBody.substringAfter("var $varWithKeyName=")
            .substringBefore(";")

        return scriptBody.replace(varWithKeyBody, "AESKeyExtractor.setKey($varWithKeyBody)")
    }
}

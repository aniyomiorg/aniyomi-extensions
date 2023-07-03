package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decodeHex
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AESKeyExtractor(private val client: OkHttpClient) {
    companion object {
        val KEY_MAP = mutableMapOf(
            // Default key. if it changes, the extractor will update it.
            "PinkBird" to "7191d608bd4deb4dc36f656c4bbca1b7".toByteArray(),
            "SapphireDuck" to "2940ba141ba490377b3f0a28ce56641a".toByteArray(), // i hate sapphire
        )

        // ..... dont try reading them.
        private val KEY_VAR_REGEX by lazy { Regex("\\.AES\\[.*?\\]\\((\\w+)\\),") }
        private val KEY_FUNC_REGEX by lazy {
            Regex(",\\w+\\.SHA1\\)\\(\\[.*?function.*?\\(\\w+=(\\w+).*?function")
        }
    }

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class ExtractorJSI(private val latch: CountDownLatch, private val prefix: String) {
        @JavascriptInterface
        fun setKey(key: String) {
            val keyBytes = when {
                key.length == 32 -> key.toByteArray()
                else -> key.decodeHex()
            }
            AESKeyExtractor.KEY_MAP.set(prefix, keyBytes)
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

        return AESKeyExtractor.KEY_MAP.get(prefix) ?: throw Exception()
    }

    private fun patchScriptFromUrl(url: String): String {
        return client.newCall(GET(url)).execute()
            .body.string()
            .let {
                patchScriptFromHtml(url.substringBeforeLast("/"), it)
            }
    }

    private fun patchScriptFromHtml(baseUrl: String, body: String): String {
        val scriptPath = body.substringAfter("src=\"assets/").substringBefore('"')
        val scriptUrl = "$baseUrl/assets/$scriptPath"
        val scriptBody = client.newCall(GET(scriptUrl)).execute().body.string()

        return when {
            KEY_FUNC_REGEX.containsMatchIn(scriptBody) -> patchScriptWithFunction(scriptBody) // Sapphire
            KEY_VAR_REGEX.containsMatchIn(scriptBody) -> patchScriptWithVar(scriptBody) // PinkBird
            else -> throw Exception() // ????
        }
    }

    private fun patchScriptWithVar(script: String): String {
        val varWithKeyName = KEY_VAR_REGEX.find(script)
            ?.groupValues
            ?.lastOrNull()
            ?: throw Exception()

        val varWithKeyBody = script.substringAfter("var $varWithKeyName=")
            .substringBefore(";")

        return script.replace(varWithKeyBody, "AESKeyExtractor.setKey($varWithKeyBody)")
    }

    private fun patchScriptWithFunction(script: String): String {
        val (match, functionName) = KEY_FUNC_REGEX.find(script)
            ?.groupValues
            ?: throw Exception()
        val patchedMatch = match.replace(
            ";function",
            ";AESKeyExtractor.setKey($functionName().toString());function",
        )
        return script.replace(match, patchedMatch)
    }
}

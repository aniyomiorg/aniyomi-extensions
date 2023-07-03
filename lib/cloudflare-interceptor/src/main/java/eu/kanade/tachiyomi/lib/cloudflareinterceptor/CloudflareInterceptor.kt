package eu.kanade.tachiyomi.lib.cloudflareinterceptor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(private val client: OkHttpClient) : Interceptor {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val originalResponse = chain.proceed(chain.request())

        // if Cloudflare anti-bot didn't block it, then do nothing and return it
        if (!(originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK)) {
            return originalResponse
        }

        return try {
            originalResponse.close()
            val request = resolveWithWebView(originalRequest, client)

            chain.proceed(request)
        } catch (e: Exception) {
            // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
            // we don't crash the entire app
            throw IOException(e)
        }
    }

    class CloudflareJSI(private val latch: CountDownLatch) {
        @JavascriptInterface
        fun leave() = latch.countDown()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolveWithWebView(request: Request, client: OkHttpClient): Request {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        val jsinterface = CloudflareJSI(latch)

        var webView: WebView? = null

        val origRequestUrl = request.url.toString()
        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = false
                userAgentString = request.header("User-Agent")
                    ?: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"
            }

            webview.addJavascriptInterface(jsinterface, "CloudflareJSI")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        // Wait a reasonable amount of time to retrieve the solution. The minimum should be
        // around 4 seconds but it can take more due to slow networks or server issues.
        latch.await(30, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val cookies = CookieManager.getInstance()
            ?.getCookie(origRequestUrl)
            ?.split(";")
            ?.mapNotNull { Cookie.parse(request.url, it) }
            ?: emptyList<Cookie>()

        // Copy webview cookies to OkHTTP cookie storage
        cookies.forEach {
            client.cookieJar.saveFromResponse(
                url = HttpUrl.Builder()
                    .scheme("http")
                    .host(it.domain)
                    .build(),
                cookies = cookies,
            )
        }

        return createRequestWithCookies(request, cookies)
    }

    private fun createRequestWithCookies(request: Request, cookies: List<Cookie>): Request {
        val convertedForThisRequest = cookies.filter {
            it.matches(request.url)
        }
        val existingCookies = Cookie.parseAll(
            request.url,
            request.headers,
        )
        val filteredExisting = existingCookies.filter { existing ->
            convertedForThisRequest.none { converted -> converted.name == existing.name }
        }

        val newCookies = filteredExisting + convertedForThisRequest
        return request.newBuilder()
            .header("Cookie", newCookies.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

        // ref: https://github.com/vvanglro/cf-clearance/blob/0d3455b5b4f299b131f357dd6e0a27316cf26f9a/cf_clearance/retry.py#L15
        private val CHECK_SCRIPT by lazy {
            """
            setInterval(() => {
                if (document.querySelector("#challenge-form") != null) {
                    // still havent passed, lets try to click in some challenges
                    const simpleChallenge = document.querySelector("#challenge-stage > div > input[type='button']")
                    if (simpleChallenge != null) simpleChallenge.click()

                    const turnstile = document.querySelector("div.hcaptcha-box > iframe")
                    if (turnstile != null) {
                        const button = turnstile.contentWindow.document.querySelector("input[type='checkbox']")
                        if (button != null) button.click()
                    }
                } else {
                    // passed
                    CloudflareJSI.leave()
                }
            }, 2500)
            """.trimIndent()
        }
    }
}

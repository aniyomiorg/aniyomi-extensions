package eu.kanade.tachiyomi.animeextension.en.nineanime.extractors

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animeextension.en.nineanime.MediaResponseBody
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VidsrcExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(embedLink: String, name: String, type: String): List<Video> {
        val hosterName = when (name) {
            "vidplay" -> "VidPlay"
            else -> "MyCloud"
        }
        val host = embedLink.toHttpUrl().host
        val apiSlug = runCatching {
            extractFromUrl(embedLink)
        }.getOrElse { return emptyList() }

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", host)
            add("Referer", URLDecoder.decode(embedLink, "UTF-8"))
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val response = client.newCall(
            GET("https://$host/$apiSlug", apiHeaders),
        ).execute()

        val data = response.parseAs<MediaResponseBody>()

        return playlistUtils.extractFromHls(
            data.result.sources.first().file,
            referer = "https://$host/",
            videoNameGen = { q -> "$hosterName - $type - $q" },
            subtitleList = data.result.tracks.toTracks(),
        )
    }

    private fun List<MediaResponseBody.Result.SubTrack>.toTracks(): List<Track> {
        return filter {
            it.kind == "captions"
        }.mapNotNull {
            runCatching {
                Track(
                    it.file,
                    it.label,
                )
            }.getOrNull()
        }
    }

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

    fun extractFromUrl(episodeUrl: String): String {
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

            webview.addJavascriptInterface(jsinterface, "ihatetheantichrist")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.clearCache(true)
                    view?.clearFormData()
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val reqUrl = request.url.toString()
                    if ("futoken" in reqUrl) {
                        return patchScript(reqUrl)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webview.loadUrl(episodeUrl)
        }

        latch.await(5, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return jsinterface.payload
    }

    private fun patchScript(scriptUrl: String): WebResourceResponse {
        val scriptBody = client.newCall(GET(scriptUrl)).execute().use { it.body.string() }
        val newBody = scriptBody.replace("return", "ihatetheantichrist.passPayload('mediainfo/'+a.join(',')+location.search);return")
        return WebResourceResponse(
            "application/javascript", // mimeType
            "utf-8", // encoding
            200, // status code
            "ok", // reason phrase
            mapOf( // response headers
                "server" to "cloudflare",
            ),
            ByteArrayInputStream(newBody.toByteArray()), // data
        )
    }
}

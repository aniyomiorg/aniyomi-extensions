package eu.kanade.tachiyomi.animeextension.de.aniworld

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JsInterceptor(private val client: OkHttpClient) : Interceptor {

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class JsObject(private val latch: CountDownLatch, private val client: OkHttpClient, var payload: String = "") {
        @JavascriptInterface
        fun passPayload(passedPayload: String) {
            payload = passedPayload
            latch.countDown()
        }
        @JavascriptInterface
        fun client(source: String): String? {
            val body = "input=${java.net.URLEncoder.encode(source, "utf-8")}&lang=de".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val solved = client.newCall(
                POST(
                    "https://engageub.pythonanywhere.com", body = body,
                    headers = Headers.headersOf(
                        "Content-Type", "application/x-www-form-urlencoded",
                        "user-agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36"
                    )
                )
            ).execute().body?.string()
            return solved
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

        val jsinterface = JsObject(latch, client)

        // JavaSrcipt bypass recaptcha FUCK GOOGLE RECAPTCHA 0.1V
        val jsScript = """
            (function(){
                let intervalIdA = setInterval(() => {
                    let iframewindow = document.querySelector('iframe[title="reCAPTCHA-Aufgabe läuft in zwei Minuten ab"]').contentWindow;
                    if (iframewindow) {
                        clearInterval(intervalIdA);
                        let audiobutton = iframewindow.document.querySelector('#recaptcha-audio-button');
                        let event = iframewindow.document.createEvent('HTMLEvents');
                        event.initEvent('click',false,false);
                        audiobutton.dispatchEvent(event);
                        let intervalIdB = setInterval(() => {
                            let source = iframewindow.document.querySelector('#audio-source').getAttribute('src');
                            if (source) {
                                clearInterval(intervalIdB);
                                let audioresponse = iframewindow.document.querySelector('#audio-response');
                                let verifybutton = iframewindow.document.querySelector('#recaptcha-verify-button');
                                var solved = window.android.client(source);
                                var tries = 0
                                while((solved == "0" || solved.includes("<") || solved.includes(">") || solved.length < 2 || solved.length > 50) && tries <= 3) {
                                    solved = window.android.client(source);
                                    if(solved == "0" || solved.includes("<") || solved.includes(">") || solved.length < 2 || solved.length > 50){
                                        tries++;
                                    } else {
                                        tries = 3
                                    }
                                }
                                if(solved == "0" || solved.includes("<") || solved.includes(">") || solved.length < 2 || solved.length > 50){
                                    window.android.passPayload("");
                                } else {
                                    audioresponse.value = solved;
                                    verifybutton.dispatchEvent(event);
                                    const originalOpen = iframewindow.XMLHttpRequest.prototype.open;
                                    iframewindow.XMLHttpRequest.prototype.open = function(method, url, async) {
                                        if(url.includes('userverify')){
                                            originalOpen.apply(this, arguments); // call the original open method
                                            this.onreadystatechange = function() {
                                                if (this.readyState === 4 && this.status === 200) {
                                                    const responseBody = this.responseText;
                                                    window.android.passPayload(responseBody);
                                                }
                                            };
                                        }
                                    };
                                }
                            }
                        }, 2000);
                    }
                }, 2000);
            })();
        """

        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        var newRequest: Request? = null

        var isDataLoaded = false

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36"
                webview.addJavascriptInterface(jsinterface, "android")
                webview.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.clearCache(true)
                        view?.clearFormData()
                        val customHtml = """
                            <html lang='de'><head> <meta charset='utf-8'> <title>Überprüfung</title> <meta name='robots' content='noindex, nofollow'> <meta name='description' content='AniWorld Stream Weiterleitung'> <meta name='keywords' content='AniWorld.to, serien stream, online stream, serien kostenlos, serien gratis, serien deutsch kostenlos, s.to, serienstream, serien gratis, kino, stream, maxdome kostenlos, netflix kostenlos, kinox.to, Android Stream, kinox.to alternative, movie2k, iPad Stream, movie4k, burning series, burning-seri.es, iphone stream, burning series app, burning series down, mobile stream, burning series serien, Onlineserien'> <meta name='viewport' content='width=device-width, initial-scale=1.0'> <link rel='icon' href='https://aniworld.to/public/img/favicon.ico'> <meta name='theme-color' content='#637cf9'> <script type='text/javascript' src='https://aniworld.to/public/js/jquery.min.js'></script> <script type='text/javascript' src='https://aniworld.to/public/js/jquery-ui.min.js'></script> <!--[if IE]> <script src='/public/js/html5shiv.min.js'></script> <script src='/public/js/respond.min.js'></script><![endif]--> <script> var init = function () { grecaptcha.render('captcha', { 'sitekey': '6LfrJ14aAAAAANcuphB2k-5B856Ky9FpOsVrXJCO', 'size': 'invisible', 'callback': securitCaptcha }); grecaptcha.execute(); }; var securitCaptcha = function (token) { ${'$'}('.securityToken').val(token); ${'$'}('.securityTokenForm').submit(); }; </script> <style type='text/css'> @import url(//fonts.googleapis.com/css?family=Open+Sans:400,600,700); html, body, div, span, applet, object, iframe, h1, h2, h3, h4, h5, h6, p, blockquote, pre, a, abbr, acronym, address, big, cite, code, del, dfn, em, img, ins, kbd, q, s, samp, small, strike, strong, sub, sup, tt, var, b, u, i, center, dl, dt, dd, ol, ul, li, form, label, legend, table, caption, tbody, tfoot, thead, tr, th, td, article, aside, canvas, details, embed, figure, figcaption, footer, header, menu, nav, output, ruby, section, summary, time, mark, audio, video { margin: 0; padding: 0 0 0 0; border: 0; vertical-align: baseline; box-sizing: border-box; } html { line-height: 1; font-family: 'Open Sans', 'Helvetica Neue', helvetica, arial, sans-serif; background: #fafafa; } html, body { height: 100%; text-align: center; } body { background: #0f1620; height: 100%; } h1, p, small { text-align: center; } h1 { color: #fff !important; font-size: 200%; } small { margin-top: 50px; color: #fff !important; display: inline-block; opacity: 0.7; } a:hover { color: #159cf5; !important; } p { color: #fff !important; font-size: 150%; margin-top: 20px; } .logo-wrapper { width: 100%; text-align: center; margin-bottom: 65px; } .logo-wrapper > a { text-align: center; } .logo-wrapper > a > .header-logo { float: none; display: inline-block; } .loader { display: inline-block; width: 60px; height: 60px; position: relative; border: 4px solid #637cf9; top: 50%; margin: 100px auto; animation: loader 2.5s infinite ease; } .loader-inner { vertical-align: top; display: inline-block; width: 100%; background-color: #637cf9; animation: loader-inner 2.5s infinite ease-in; } @keyframes loader { 0% { transform: rotate(0deg); } 25% { transform: rotate(180deg); } 50% { transform: rotate(180deg); } 75% { transform: rotate(360deg); } 100% { transform: rotate(360deg); } } @keyframes loader-inner { 0% { height: 0%; } 25% { height: 0%; } 50% { height: 100%; } 75% { height: 100%; } 100% { height: 0%; } } </style><style class='darkreader darkreader--cors' media='screen'>html, body, div, span, applet, object, iframe, h1, h2, h3, h4, h5, h6, p, blockquote, pre, a, abbr, acronym, address, big, cite, code, del, dfn, em, img, ins, kbd, q, s, samp, small, strike, strong, sub, sup, tt, var, b, u, i, center, dl, dt, dd, ol, ul, li, form, label, legend, table, caption, tbody, tfoot, thead, tr, th, td, article, aside, canvas, details, embed, figure, figcaption, footer, header, menu, nav, output, ruby, section, summary, time, mark, audio, video { margin: 0; padding: 0 0 0 0; border: 0; vertical-align: baseline; box-sizing: border-box; } html { line-height: 1; font-family: 'Open Sans', 'Helvetica Neue', helvetica, arial, sans-serif; background: #fafafa; } html, body { height: 100%; text-align: center; } body { background: #0f1620; height: 100%; } h1, p, small { text-align: center; } h1 { color: #fff !important; font-size: 200%; } small { margin-top: 50px; color: #fff !important; display: inline-block; opacity: 0.7; } a:hover { color: #159cf5; !important; } p { color: #fff !important; font-size: 150%; margin-top: 20px; } .logo-wrapper { width: 100%; text-align: center; margin-bottom: 65px; } .logo-wrapper > a { text-align: center; } .logo-wrapper > a > .header-logo { float: none; display: inline-block; } .loader { display: inline-block; width: 60px; height: 60px; position: relative; border: 4px solid #637cf9; top: 50%; margin: 100px auto; animation: loader 2.5s infinite ease; } .loader-inner { vertical-align: top; display: inline-block; width: 100%; background-color: #637cf9; animation: loader-inner 2.5s infinite ease-in; } @keyframes loader { 0% { transform: rotate(0deg); } 25% { transform: rotate(180deg); } 50% { transform: rotate(180deg); } 75% { transform: rotate(360deg); } 100% { transform: rotate(360deg); } } @keyframes loader-inner { 0% { height: 0%; } 25% { height: 0%; } 50% { height: 100%; } 75% { height: 100%; } 100% { height: 0%; } }</style><style class='darkreader darkreader--sync' media='screen'></style> </head> <body> <div id='wrapper'> <div class='container' style='padding: 10px'> <form method='GET' class='securityTokenForm'> <input type='hidden' name='token' class='securityToken'> <input type='hidden' name='original' value=''> </form> <h1>Dein Stream wird überprüft...</h1> <p>Falls nötig, löse bitte das Captcha, um die Serie weiterschauen zu können.</p> <span class='loader'><span class='loader-inner'></span></span> <div id='captcha'></div> <script src='https://www.google.com/recaptcha/api.js?onload=init&amp;render=explicit' async='' defer=''></script> </div> </div> </body></html>
                        """
                        if (!isDataLoaded) {
                            view?.loadDataWithBaseURL("https://www.google.com/", customHtml, "text/html", "UTF-8", null)
                            isDataLoaded = true
                        }
                        view?.evaluateJavascript(jsScript) {}
                    }
                }
                webView?.loadUrl(origRequestUrl, headers)
            }
        }

        latch.await(120, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        newRequest = GET(request.url.toString(), headers = Headers.headersOf("url", jsinterface.payload.substringAfter("uvresp\",\"").substringBefore("\",")))
        return newRequest
    }
}

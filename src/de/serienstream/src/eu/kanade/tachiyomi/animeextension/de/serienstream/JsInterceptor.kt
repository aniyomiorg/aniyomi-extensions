package eu.kanade.tachiyomi.animeextension.de.serienstream

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

class JsInterceptor : Interceptor {

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

        // JavaSrcipt bypass recaptcha FUCK GOOGLE RECAPTCHA v1.0
        val jsScript = """
            function audioBufferToWav(buffer, opt) {
                opt = opt || {}

                var numChannels = buffer.numberOfChannels
                var sampleRate = buffer.sampleRate
                var format = opt.float32 ? 3 : 1
                var bitDepth = format === 3 ? 32 : 16

                var result
                if (numChannels === 2) {
                    result = interleave(buffer.getChannelData(0), buffer.getChannelData(1))
                } else {
                    result = buffer.getChannelData(0)
                }

                return encodeWAV(result, format, sampleRate, numChannels, bitDepth)
            }

            function encodeWAV(samples, format, sampleRate, numChannels, bitDepth) {
                var bytesPerSample = bitDepth / 8
                var blockAlign = numChannels * bytesPerSample

                var buffer = new ArrayBuffer(44 + samples.length * bytesPerSample)
                var view = new DataView(buffer)

                /* RIFF identifier */
                writeString(view, 0, 'RIFF')
                /* RIFF chunk length */
                view.setUint32(4, 36 + samples.length * bytesPerSample, true)
                /* RIFF type */
                writeString(view, 8, 'WAVE')
                /* format chunk identifier */
                writeString(view, 12, 'fmt ')
                /* format chunk length */
                view.setUint32(16, 16, true)
                /* sample format (raw) */
                view.setUint16(20, format, true)
                /* channel count */
                view.setUint16(22, numChannels, true)
                /* sample rate */
                view.setUint32(24, sampleRate, true)
                /* byte rate (sample rate * block align) */
                view.setUint32(28, sampleRate * blockAlign, true)
                /* block align (channel count * bytes per sample) */
                view.setUint16(32, blockAlign, true)
                /* bits per sample */
                view.setUint16(34, bitDepth, true)
                /* data chunk identifier */
                writeString(view, 36, 'data')
                /* data chunk length */
                view.setUint32(40, samples.length * bytesPerSample, true)
                if (format === 1) { // Raw PCM
                    floatTo16BitPCM(view, 44, samples)
                } else {
                    writeFloat32(view, 44, samples)
                }

                return buffer
            }

            function interleave(inputL, inputR) {
                var length = inputL.length + inputR.length
                var result = new Float32Array(length)

                var index = 0
                var inputIndex = 0

                while (index < length) {
                    result[index++] = inputL[inputIndex]
                    result[index++] = inputR[inputIndex]
                    inputIndex++
                }
                return result
            }

            function writeFloat32(output, offset, input) {
                for (var i = 0; i < input.length; i++, offset += 4) {
                    output.setFloat32(offset, input[i], true)
                }
            }

            function floatTo16BitPCM(output, offset, input) {
                for (var i = 0; i < input.length; i++, offset += 2) {
                    var s = Math.max(-1, Math.min(1, input[i]))
                    output.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true)
                }
            }

            function writeString(view, offset, string) {
                for (var i = 0; i < string.length; i++) {
                    view.setUint8(offset + i, string.charCodeAt(i))
                }
            }

            async function normalizeAudio(buffer) {
                const ctx = new AudioContext();
                const audioBuffer = await ctx.decodeAudioData(buffer);
                ctx.close();

                const offlineCtx = new OfflineAudioContext(
                    1,
                    audioBuffer.duration * 16000,
                    16000
                );
                const source = offlineCtx.createBufferSource();
                source.connect(offlineCtx.destination);
                source.buffer = audioBuffer;
                source.start();

                return offlineCtx.startRendering();
            }

            async function sliceAudio({
                                          audioBuffer,
                                          start,
                                          end
                                      }) {
                const sampleRate = audioBuffer.sampleRate;
                const channels = audioBuffer.numberOfChannels;

                const startOffset = sampleRate * start;
                const endOffset = sampleRate * end;
                const frameCount = endOffset - startOffset;

                const ctx = new AudioContext();
                const audioSlice = ctx.createBuffer(channels, frameCount, sampleRate);
                ctx.close();

                const tempArray = new Float32Array(frameCount);
                for (var channel = 0; channel < channels; channel++) {
                    audioBuffer.copyFromChannel(tempArray, channel, startOffset);
                    audioSlice.copyToChannel(tempArray, channel, 0);
                }

                return audioSlice;
            }

            async function prepareAudio(audio) {
                const audioBuffer = await normalizeAudio(audio);

                const audioSlice = await sliceAudio({
                    audioBuffer,
                    start: 1.5,
                    end: audioBuffer.duration - 1.5
                });

                return audioBufferToWav(audioSlice);
            }

            async function getWitSpeechApiResult(audioUrl) {
                var audioRsp = await fetch(audioUrl);
                var t = 0;
                while(audioRsp.status === 404 && t <= 2){
                    t++;
                    audioRsp = await fetch(audioUrl);
                }
                const audioContent = await prepareAudio(await audioRsp.arrayBuffer());
                const result = {};

                const rsp = await fetch('https://api.wit.ai/speech?v=20221114', {
                    mode: 'cors',
                    method: 'POST',
                    headers: {
                        Authorization: 'Bearer ' + 'YZLWLZHOWH7MZR636L5IGJW66R43CEID'
                    },
                    body: new Blob([audioContent], {
                        type: 'audio/wav'
                    })
                });

                if (rsp.status !== 200) {
                    if (rsp.status === 429) {
                        result.errorId = 'error_apiQuotaExceeded';
                        result.errorTimeout = 6000;
                    } else {
                        throw new Error('API response:' + rsp.status + ',' + await rsp.text());
                    }
                } else {
                    const data = JSON.parse((await rsp.text()).split('\r\n').at(-1)).text;
                    if (data) {
                        result.text = data.trim();
                    }
                }
                return result.text;
            }

            async function main() {
              let intervalIdA = setInterval(() => {
                let iframewindow = document.querySelector("iframe[src*='recaptcha/api2']:not([src*=anchor])").contentWindow;
                if (iframewindow) {
                  clearInterval(intervalIdA);
                  let audiobutton = iframewindow.document.querySelector('#recaptcha-audio-button');
                  let event = iframewindow.document.createEvent('HTMLEvents');
                  event.initEvent('click', false, false);
                  audiobutton.dispatchEvent(event);
                  let intervalIdB = setInterval(async () => {
                    let audio = iframewindow.document.querySelector('#audio-source');
                    let source = audio.getAttribute('src');
                    if (source) {
                      clearInterval(intervalIdB);
                      // Prevent 404 status loop
                      const response = await fetch(source);
                      if (response.ok) {
                        let audioresponse = iframewindow.document.querySelector('#audio-response');
                        let verifybutton = iframewindow.document.querySelector('#recaptcha-verify-button');
                        var tries = 0;
                        let intervalIdC = setInterval(async () => {
                          var solved = null
                          solved = await getWitSpeechApiResult(source);
                          tries++;
                          if (solved != null) {
                            clearInterval(intervalIdC);
                            audioresponse.value = solved;
                            verifybutton.dispatchEvent(event);
                            const originalOpen = iframewindow.XMLHttpRequest.prototype.open;
                            iframewindow.XMLHttpRequest.prototype.open = function (method, url, async) {
                              if (url.includes('userverify')) {
                                originalOpen.apply(this, arguments); // call the original open method
                                this.onreadystatechange = function () {
                                  if (this.readyState === 4 && this.status === 200) {
                                    const responseBody = this.responseText;
                                    window.android.passPayload(responseBody);
                                  }
                                };
                              }
                            };
                          } else if (tries >= 2) {
                            clearInterval(intervalIdC);
                            window.android.passPayload("");
                          }
                        }, 2000);
                      } else {
                        window.android.passPayload("Audio file not found");
                      }
                    }
                  }, 2000);
                }
              }, 2000);
            };

            // Prevent async-related problems with stupid webviews
            setTimeout(async () => await main(), 0);
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
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (request?.url.toString().contains("https://www.google.com/?token=")) {
                            jsinterface.payload = request?.url.toString().substringAfter("?token=").substringBefore("&original=")
                            latch.countDown()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.clearCache(true)
                        view?.clearFormData()
                        val customHtml = """
                                <html lang="de">
                                <head>
                                    <meta charset="utf-8">
                                    <title>Überprüfung</title>
                                    <meta name="robots" content="noindex, nofollow">
                                    <meta name="description" content="S.to Stream Weiterleitung">
                                    <meta name="keywords" content="S.to, serien stream, online stream, serien kostenlos, serien gratis, serien deutsch kostenlos, s.to, serienstream, serien gratis, kino, stream, maxdome kostenlos, netflix kostenlos, kinox.to, Android Stream, kinox.to alternative, movie2k, iPad Stream, movie4k, burning series, burning-seri.es, iphone stream, burning series app, burning series down, mobile stream, burning series serien, Onlineserien">
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                    <link rel="icon" href="/favicon.ico">
                                    <link rel="manifest" href="/site.webmanifest">
                                    <link rel="mask-icon" href="/safari-pinned-tab.svg" color="#5bbad5">
                                    <link rel="apple-touch-icon" href="https://zrt5351b7er9.static-webarchive.org/img/touch-icon-iphone.png">
                                    <link rel="apple-touch-icon" sizes="76x76" href="https://zrt5351b7er9.static-webarchive.org/img/touch-icon-ipad.png">
                                    <link rel="apple-touch-icon" sizes="120x120" href="https://zrt5351b7er9.static-webarchive.org/img/touch-icon-iphone-retina.png">
                                    <link rel="apple-touch-icon" sizes="152x152" href="https://zrt5351b7er9.static-webarchive.org/img/touch-icon-ipad-retina.png">
                                    <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
                                    <link rel="icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
                                    <link rel="icon" type="image/png" sizes="16x16" href="/favicon-16x16.png">
                                    <script type="text/javascript" src="https://zrt5351b7er9.static-webarchive.org/js/jquery.min.js"></script>
                                    <script type="text/javascript" src="https://zrt5351b7er9.static-webarchive.org/js/jquery-ui.min.js"></script>
                                    <!--[if IE]>
                                    <script src="https://zrt5351b7er9.static-webarchive.org/js/html5shiv.min.js"></script>
                                    <script src="https://zrt5351b7er9.static-webarchive.org/js/respond.min.js"></script><![endif]-->
                                    <script>
                                        var init = function () {
                                            grecaptcha.render('captcha', {
                                                'sitekey': '6LeBCHsaAAAAABiuOuoPJ_0E1Ny6OF5mAnuqDoK7', 'size': 'invisible', 'callback': securitCaptcha
                                            });
                                            grecaptcha.execute();
                                        };
                                        var securitCaptcha = function (token) {
                                            ${'$'}('.securityToken').val(token);
                                            ${'$'}('.securityTokenForm').submit();
                                        };
                                    </script>
                                    <style type="text/css">
                                        @import url(//fonts.googleapis.com/css?family=Open+Sans:400,600,700);

                                        html, body, div, span, applet, object, iframe, h1, h2, h3, h4, h5, h6, p, blockquote, pre, a, abbr, acronym, address, big, cite, code, del, dfn, em, img, ins, kbd, q, s, samp, small, strike, strong, sub, sup, tt, var, b, u, i, center, dl, dt, dd, ol, ul, li, form, label, legend, table, caption, tbody, tfoot, thead, tr, th, td, article, aside, canvas, details, embed, figure, figcaption, footer, header, menu, nav, output, ruby, section, summary, time, mark, audio, video {
                                            margin: 0;
                                            padding: 0 0 0 0;
                                            border: 0;
                                            vertical-align: baseline;
                                            box-sizing: border-box;
                                        }

                                        html {
                                            line-height: 1;
                                            font-family: 'Open Sans', 'Helvetica Neue', helvetica, arial, sans-serif;
                                            background: #fafafa;
                                        }

                                        html,
                                        body {
                                            height: 100%;
                                            text-align: center;
                                        }

                                        body {
                                            background: #0f1620;
                                            height: 100%;
                                        }

                                        h1, p, small {
                                            text-align: center;
                                        }

                                        h1 {
                                            color: #fff !important;
                                            font-size: 200%;
                                        }

                                        small {
                                            margin-top: 50px;
                                            color: #fff !important;
                                            display: inline-block;
                                            opacity: 0.7;
                                        }

                                        a:hover {
                                            color: #159cf5;
                                        !important;
                                        }

                                        p {
                                            color: #fff !important;
                                            font-size: 150%;
                                            margin-top: 20px;
                                        }

                                        .logo-wrapper {
                                            width: 100%;
                                            text-align: center;
                                            margin-bottom: 65px;
                                        }

                                        .logo-wrapper > a {
                                            text-align: center;
                                        }

                                        .logo-wrapper > a > .header-logo {
                                            float: none;
                                            display: inline-block;
                                        }

                                        .sk-double-bounce {
                                            width: 80px;
                                            height: 80px;
                                            position: relative;
                                            margin: 100px auto;
                                        }

                                        .sk-double-bounce .sk-child {
                                            width: 100%;
                                            height: 100%;
                                            border-radius: 50%;
                                            background-color: #44adf3;
                                            opacity: 0.6;
                                            position: absolute;
                                            top: 0;
                                            left: 0;
                                            -webkit-animation: sk-doubleBounce 2s infinite ease-in-out;
                                            animation: sk-doubleBounce 2s infinite ease-in-out;
                                        }

                                        .sk-double-bounce .sk-double-bounce2 {
                                            -webkit-animation-delay: -1.0s;
                                            animation-delay: -1.0s;
                                        }

                                        @-webkit-keyframes sk-doubleBounce {
                                            0%, 100% {
                                                -webkit-transform: scale(0);
                                                transform: scale(0);
                                            }
                                            50% {
                                                -webkit-transform: scale(1);
                                                transform: scale(1);
                                            }
                                        }

                                        @keyframes sk-doubleBounce {
                                            0%, 100% {
                                                -webkit-transform: scale(0);
                                                transform: scale(0);
                                            }
                                            50% {
                                                -webkit-transform: scale(1);
                                                transform: scale(1);
                                            }
                                        }
                                    </style>
                                </head>
                                <body>
                                <div id="wrapper">
                                    <div class="container" style="padding: 10px">
                                        <form method="GET" class="securityTokenForm">
                                            <input type="hidden" name="token" class="securityToken"/>
                                            <input type="hidden" name="original"
                                                   value=""/>
                                        </form>
                                        <h1>Dein Stream wird überprüft...</h1>
                                        <p>Falls nötig, löse bitte das Captcha, um die Serie weiterschauen zu können.</p>
                                        <div class="sk-double-bounce">
                                            <div class="sk-child sk-double-bounce1"></div>
                                            <div class="sk-child sk-double-bounce2"></div>
                                        </div>

                                        <div id="captcha"></div>

                                        <script src="https://www.google.com/recaptcha/api.js?onload=init&render=explicit" async defer></script>
                                    </div>
                                </div>
                                </body>
                                </html>
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

        latch.await(60, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        newRequest = GET(request.url.toString(), headers = Headers.headersOf("url", jsinterface.payload.substringAfter("uvresp\",\"").substringBefore("\",")))
        return newRequest
    }
}

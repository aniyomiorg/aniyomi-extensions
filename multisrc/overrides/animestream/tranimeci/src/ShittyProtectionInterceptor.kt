package eu.kanade.tachiyomi.animeextension.tr.tranimeci

import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class ShittyProtectionInterceptor(private val client: OkHttpClient) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        // ignore non-protected requests
        if (response.code != 202) return response
        return try {
            chain.proceed(bypassProtection(request, response))
        } catch (e: Throwable) {
            // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
            // we don't crash the entire app
            e.printStackTrace()
            throw IOException(e)
        }
    }

    private fun bypassProtection(request: Request, response: Response): Request {
        val doc = response.asJsoup()

        val script = doc.selectFirst("script:containsData(slowAES)")!!.data()

        val slowAES = doc.selectFirst("script[src*=min.js]")!!.attr("abs:src").let { url ->
            client.newCall(GET(url)).execute().body.string()
        }

        val patchedScript = slowAES + "\n" + ADDITIONAL_FUNCTIONS + script
            .replace("document.cookie=", "")
            .replace("location.href", "// ")

        val cookieString = QuickJs.create().use {
            it.evaluate(patchedScript)?.toString()
        }!!

        val cookie = Cookie.parse(request.url, cookieString)!!

        client.cookieJar.saveFromResponse(request.url, listOf(cookie))

        val headers = request.headers.newBuilder()
            .add("Cookie", cookie.toString())
            .build()

        return GET(request.url.toString(), headers)
    }

    companion object {
        private val ADDITIONAL_FUNCTIONS get() = """
            // QJS doesnt have atob(b64dec) >:(
            atob = function(s) {
                var e={},i,b=0,c,x,l=0,a,r='',w=String.fromCharCode,L=s.length;
                var A="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                for(i=0;i<64;i++){e[A.charAt(i)]=i;}
                for(x=0;x<L;x++){
                    c=e[s.charAt(x)];b=(b<<6)+c;l+=6;
                    while(l>=8){((a=(b>>>(l-=8))&0xff)||(x<(L-2)))&&(r+=w(a));}
                }
                return r;
            };
        """.trimIndent()
    }
}

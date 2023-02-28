package eu.kanade.tachiyomi.animeextension.de.animeloads

import android.util.Log
import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.GET
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class DdosGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Check if DDos-GUARD is on
        if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) {
            return response
        }

        response.close()
        val cookies = cookieManager.getCookie(originalRequest.url.toString())
        val oldCookie = if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(originalRequest.url, it) }
        } else {
            emptyList()
        }
        Log.i("newCookie", "OldCookies: $oldCookie")
        val ddg2Cookie = oldCookie.firstOrNull { it.name == "__ddg2_" }
        if (!ddg2Cookie?.value.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        val newCookie = getNewCookie(originalRequest.url) ?: return chain.proceed(originalRequest)
        val newCookieHeader = buildString {
            (oldCookie + newCookie).forEachIndexed { index, cookie ->
                if (index > 0) append("; ")
                append(cookie.name).append('=').append(cookie.value)
            }
        }

        return chain.proceed(originalRequest.newBuilder().addHeader("cookie", newCookieHeader).build())
    }

    fun getNewCookie(url: HttpUrl): Cookie? {
        val cookies = cookieManager.getCookie(url.toString())
        val oldCookie = if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
        val ddg2Cookie = oldCookie.firstOrNull { it.name == "__ddg2_" }
        if (!ddg2Cookie?.value.isNullOrEmpty()) {
            return ddg2Cookie
        }
        val wellKnown = client.newCall(GET("https://check.ddos-guard.net/check.js"))
            .execute().body.string()
            .substringAfter("'", "")
            .substringBefore("'", "")
        val checkUrl = "${url.scheme}://${url.host + wellKnown}"
        return client.newCall(GET(checkUrl)).execute().header("set-cookie")?.let {
            Cookie.parse(url, it)
        }
    }

    companion object {
        private val ERROR_CODES = listOf(403)
        private val SERVER_CHECK = listOf("ddos-guard")
    }
}

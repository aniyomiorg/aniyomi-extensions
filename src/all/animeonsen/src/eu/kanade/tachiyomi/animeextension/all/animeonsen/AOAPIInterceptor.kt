package eu.kanade.tachiyomi.animeextension.all.animeonsen

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class AOAPIInterceptor(client: OkHttpClient) : Interceptor {

    private val token: String

    init {
        val cookie = client.cookieJar
            .loadForRequest("https://animeonsen.xyz".toHttpUrl())
            .find { it.name == "ao.session" }?.value
            ?: client.newCall(GET("https://animeonsen.xyz")).execute().header("set-cookie")

        token = String(
            Base64.decode(
                java.net.URLDecoder.decode(cookie, "utf-8"),
                Base64.DEFAULT
            )
        )

        Log.i("bruh", token)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val newRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        return chain.proceed(newRequest)
    }
}

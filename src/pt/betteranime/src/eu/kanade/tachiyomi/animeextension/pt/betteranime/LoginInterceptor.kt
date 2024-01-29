package eu.kanade.tachiyomi.animeextension.pt.betteranime

import android.util.Base64
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

internal class LoginInterceptor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val headers: Headers,
) : Interceptor {
    private val recapBypasser by lazy { RecaptchaV3Bypasser(client, headers) }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalResponse = chain.proceed(originalRequest)
        if (!originalResponse.request.url.encodedPath.contains("/dmca")) {
            return originalResponse
        }
        originalResponse.close()

        val (token, recaptchaToken) = recapBypasser.getRecaptchaToken("$baseUrl/login")

        if (recaptchaToken.isBlank()) throw IOException(FAILED_AUTOLOGIN_MESSAGE)

        val formBody = FormBody.Builder()
            .add("_token", token)
            .add("g-recaptcha-response", recaptchaToken)
            .add("email", String(Base64.decode("aGVmaWczNTY0NUBuYW1ld29rLmNvbQ==", Base64.DEFAULT)))
            .add("password", String(Base64.decode("SE1HNFdoVEI0QnRJWTlIdg==", Base64.DEFAULT)))
            .build()

        val loginRes = chain.proceed(POST("$baseUrl/login", headers, formBody))
        loginRes.close()
        if (!loginRes.isSuccessful) throw IOException(FAILED_AUTOLOGIN_MESSAGE)

        return chain.proceed(originalRequest)
    }

    companion object {
        private const val FAILED_AUTOLOGIN_MESSAGE = "Falha na tentativa de logar automaticamente! " +
            "Tente manualmente na WebView."
    }
}

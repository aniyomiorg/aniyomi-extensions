package eu.kanade.tachiyomi.animeextension.all.consumyroll

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.HttpURLConnection

class AccessTokenInterceptor(
    private val json: Json,
    private val preferences: SharedPreferences
) : Interceptor {
    private var accessToken = preferences.getString("access_token", null) ?: ""

    override fun intercept(chain: Interceptor.Chain): Response {
        if (accessToken.isBlank()) accessToken = refreshAccessToken()

        val request = chain.request().newBuilder()
            .header("authorization", accessToken)
            .build()

        val response = chain.proceed(request)

        when (response.code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                synchronized(this) {
                    response.close()
                    val newAccessToken = refreshAccessToken()
                    // Access token is refreshed in another thread.
                    if (accessToken != newAccessToken) {
                        accessToken = newAccessToken
                        return chain.proceed(
                            newRequestWithAccessToken(chain.request(), newAccessToken)
                        )
                    }

                    // Need to refresh an access token
                    val updatedAccessToken = refreshAccessToken()
                    accessToken = updatedAccessToken
                    // Retry the request
                    return chain.proceed(
                        newRequestWithAccessToken(chain.request(), updatedAccessToken)
                    )
                }
            }
            else -> return response
        }
    }

    private fun newRequestWithAccessToken(request: Request, accessToken: String): Request {
        return request.newBuilder()
            .header("authorization", accessToken)
            .build()
    }

    private fun refreshAccessToken(): String {
        val client = OkHttpClient().newBuilder().build()
        val headers = Headers.headersOf(
            "Content-Type", "application/x-www-form-urlencoded",
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:106.0) Gecko/20100101 Firefox/106.0",
            "Authorization", "Basic a3ZvcGlzdXZ6Yy0teG96Y21kMXk6R21JSTExenVPVnRnTjdlSWZrSlpibzVuLTRHTlZ0cU8="
        )
        val postBody = "grant_type=client_id".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val response = client.newCall(POST("https://beta-api.crunchyroll.com/auth/v1/token", headers, postBody)).execute()
        val respBody = response.body!!.string()
        val parsedJson = json.decodeFromString<AccessToken>(respBody)
        val token = "${parsedJson.token_type} ${parsedJson.access_token}"
        preferences.edit().putString("access_token", token).apply()
        return token
    }
}

package eu.kanade.tachiyomi.animeextension.all.kamyroll

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.HttpURLConnection

class AccessTokenInterceptor(val baseUrl: String, val json: Json, val preferences: SharedPreferences) : Interceptor {
    private val deviceId = randomId()
    private var accessToken = preferences.getString("access_token", null) ?: ""

    override fun intercept(chain: Interceptor.Chain): Response {
        if (accessToken.isBlank()) accessToken = refreshAccessToken()

        val request = if (chain.request().url.toString().contains("kamyroll")) {
            chain.request().newBuilder()
                .header("authorization", accessToken)
                .build()
        } else {
            chain.request()
        }
        val response = chain.proceed(request)

        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            synchronized(this) {
                response.close()
                val newAccessToken = refreshAccessToken()
                // Access token is refreshed in another thread.
                if (accessToken != newAccessToken) {
                    accessToken = newAccessToken
                    return chain.proceed(newRequestWithAccessToken(chain.request(), newAccessToken))
                }

                // Need to refresh an access token
                val updatedAccessToken = refreshAccessToken()
                accessToken = updatedAccessToken
                // Retry the request
                return chain.proceed(newRequestWithAccessToken(chain.request(), updatedAccessToken))
            }
        }

        return response
    }

    private fun newRequestWithAccessToken(request: Request, accessToken: String): Request {
        return request.newBuilder()
            .header("authorization", accessToken)
            .build()
    }

    private fun refreshAccessToken(): String {
        val client = OkHttpClient().newBuilder().build()
        val url = "$baseUrl/auth/v1/token".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("device_id", deviceId)
            .addQueryParameter("device_type", "aniyomi")
            .addQueryParameter("access_token", "HMbQeThWmZq4t7w")
            .build()
        val response = client.newCall(GET(url.toString())).execute()
        val parsedJson = json.decodeFromString<AccessToken>(response.body!!.string())
        val token = "${parsedJson.token_type} ${parsedJson.access_token}"
        preferences.edit().putString("access_token", token).apply()
        return token
    }

    // Random 15 length string
    private fun randomId(): String {
        return (0..14).joinToString("") {
            (('0'..'9') + ('a'..'f')).random().toString()
        }
    }
}

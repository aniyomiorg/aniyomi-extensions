package eu.kanade.tachiyomi.animeextension.all.kamyroll

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.HttpURLConnection

class AccessTokenInterceptor(val baseUrl: String, val json: Json, val preferences: SharedPreferences) : Interceptor {
    private val deviceId = randomId()

    override fun intercept(chain: Interceptor.Chain): Response {
        val accessToken = getAccessToken()
        val request = chain.request().newBuilder()
            .header("authorization", accessToken)
            .build()
        val response = chain.proceed(request)

        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            synchronized(this) {
                response.close()
                val newAccessToken = refreshAccessToken()
                // Access token is refreshed in another thread.
                if (accessToken != newAccessToken) {
                    return chain.proceed(newRequestWithAccessToken(request, newAccessToken))
                }

                // Need to refresh an access token
                val updatedAccessToken = refreshAccessToken()
                // Retry the request
                return chain.proceed(newRequestWithAccessToken(request, updatedAccessToken))
            }
        }

        return response
    }

    private fun newRequestWithAccessToken(request: Request, accessToken: String): Request {
        return request.newBuilder()
            .header("authorization", accessToken)
            .build()
    }

    private fun getAccessToken(): String {
        return preferences.getString("access_token", null) ?: ""
    }

    private fun refreshAccessToken(): String {
        val client = OkHttpClient().newBuilder().build()
        val formData = FormBody.Builder()
            .add("device_id", deviceId)
            .add("device_type", "aniyomi")
            .add("access_token", "HMbQeThWmZq4t7w")
            .build()
        val response = client.newCall(POST(url = "$baseUrl/auth/v1/token", body = formData)).execute()
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

package eu.kanade.tachiyomi.animeextension.all.kamyroll

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.HttpURLConnection

class AccessTokenInterceptor(
    private val baseUrl: String,
    private val json: Json,
    private val preferences: SharedPreferences
) : Interceptor {
    private var accessToken = preferences.getString(TOKEN_PREF_KEY, null) ?: ""

    override fun intercept(chain: Interceptor.Chain): Response {
        if (accessToken.isBlank()) accessToken = refreshAccessToken()

        val parsed = json.decodeFromString<AccessToken>(accessToken)
        val request = newRequestWithAccessToken(chain.request(), "${parsed.token_type} ${parsed.access_token}")

        val response = chain.proceed(request)

        when (response.code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                synchronized(this) {
                    response.close()
                    // Access token is refreshed in another thread.
                    accessToken = refreshAccessToken()
                    val newParsed = json.decodeFromString<AccessToken>(accessToken)
                    // Retry the request
                    return chain.proceed(
                        newRequestWithAccessToken(chain.request(), "${newParsed.token_type} ${newParsed.access_token}")
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

    fun refreshAccessToken(): String {
        val client = OkHttpClient().newBuilder().build()
        val response = client.newCall(GET("$baseUrl/token")).execute()
        val parsedJson = json.decodeFromString<AccessToken>(response.body!!.string()).toJsonString()
        preferences.edit().putString(TOKEN_PREF_KEY, parsedJson).apply()
        return parsedJson
    }

    private fun AccessToken.toJsonString(): String {
        return json.encodeToString(this)
    }

    companion object {
        val TOKEN_PREF_KEY = "access_token_data"
    }
}

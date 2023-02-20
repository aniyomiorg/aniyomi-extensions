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
    private val json: Json,
    private val preferences: SharedPreferences
) : Interceptor {
    private var accessToken = preferences.getString("access_token", null).let {
        if (it.isNullOrBlank()) {
            null
        } else {
            json.decodeFromString<AccessToken>(it)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (accessToken == null) accessToken = refreshAccessToken()

        val request = chain.request().newBuilder()
            .header("authorization", "${accessToken!!.token_type} ${accessToken!!.access_token}")
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
                            newRequestWithAccessToken(chain.request(), "${accessToken!!.token_type} ${accessToken!!.access_token}")
                        )
                    }

                    // Need to refresh an access token
                    val updatedAccessToken = refreshAccessToken()
                    accessToken = updatedAccessToken
                    // Retry the request
                    return chain.proceed(
                        newRequestWithAccessToken(chain.request(), "${accessToken!!.token_type} ${accessToken!!.access_token}")
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

    private fun refreshAccessToken(): AccessToken {
        val client = OkHttpClient().newBuilder().build()
        val response = client.newCall(GET("https://cronchy.consumet.stream/token")).execute()
        val parsedJson = json.decodeFromString<AccessToken>(response.body!!.string())
        preferences.edit().putString("access_token", parsedJson.toJsonString()).apply()
        return parsedJson
    }

    private fun AccessToken.toJsonString(): String {
        return json.encodeToString(this)
    }
}

package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException

class AuthInterceptor(
    private val preferences: SharedPreferences,
    private val client: OkHttpClient,
    private val baseUrl: String,
) : Interceptor {
    private var currentApiKey = preferences.apiKey

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val response = if (chain.request().url.queryParameter("api_key") == null) {
            val apiKey = currentApiKey.ifEmpty { updateCredentials().first }
            chain.proceed(originalRequest.createNewRequest(apiKey))
        } else {
            chain.proceed(originalRequest)
        }

        return if (response.code == 401) {
            response.close()
            val newApiKey = updateCredentials().first
            chain.proceed(originalRequest.createNewRequest(newApiKey))
        } else {
            response
        }
    }

    private fun Request.createNewRequest(apiKey: String): Request {
        val newUrl = this.url.newBuilder()
            .addQueryParameter("api_key", apiKey)
            .build()

        return this.newBuilder()
            .url(newUrl)
            .build()
    }

    fun updateCredentials(): Pair<String, String> {
        val username = preferences.username
        val password = preferences.password
        val loginData = authenticate(username, password)
        val apiKey = loginData.accessToken
        val userId = loginData.sessionInfo.userId

        currentApiKey = apiKey
        preferences.edit()
            .putString(Jellyfin.APIKEY_KEY, apiKey)
            .putString(Jellyfin.USERID_KEY, userId)
            .apply()
        return Pair(apiKey, userId)
    }

    private fun authenticate(username: String, password: String): LoginDto {
        val deviceId = preferences.deviceId.ifEmpty {
            val id = randomString()
            preferences.edit().putString(DEVICEID_KEY, id).apply()
            id
        }

        val aniyomiVersion = AppInfo.getVersionName()
        val androidVersion = Build.VERSION.RELEASE
        val authHeaders = Headers.headersOf(
            "X-Emby-Authorization",
            """MediaBrowser Client="$CLIENT", Device="Android $androidVersion", DeviceId="$deviceId", Version="$aniyomiVersion"""",
        )

        val body = JSON.encodeToString(
            buildJsonObject {
                put("Username", username)
                put("Pw", password)
            },
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        return try {
            client.newCall(
                POST("$baseUrl/Users/AuthenticateByName", headers = authHeaders, body = body),
            ).execute().parseAs<LoginDto>()
        } catch (e: SerializationException) {
            Log.e(LOG_TAG, e.message ?: "")
            throw IOException("Failed to login")
        }
    }

    private fun randomString(length: Int = 16): String {
        val charPool = ('a'..'z') + ('0'..'9')

        return buildString(length) {
            for (i in 0 until length) {
                append(charPool.random())
            }
        }
    }

    private val SharedPreferences.deviceId
        get() = getString(DEVICEID_KEY, "")!!

    companion object {
        private const val DEVICEID_KEY = "device_id"
        private const val CLIENT = "Aniyomi"
        private const val LOG_TAG = "JellyfinAuthenticator"
    }
}

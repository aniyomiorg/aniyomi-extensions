package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class JellyfinAuthenticator(
    private val preferences: SharedPreferences,
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    fun login(username: String, password: String): Pair<String?, String?> {
        return try {
            val authResult = authenticateWithPassword(username, password)
                ?: throw Exception()
            val key = authResult["AccessToken"]!!.jsonPrimitive.content
            val userId = authResult["SessionInfo"]!!.jsonObject["UserId"]!!.jsonPrimitive.content
            saveLogin(key, userId)
            Pair(key, userId)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    private fun authenticateWithPassword(username: String, password: String): JsonObject? {
        var deviceId = getPrefDeviceId()
        if (deviceId.isNullOrEmpty()) {
            deviceId = getRandomString()
            setPrefDeviceId(deviceId)
        }
        val version = getVersion() ?: "10.8.7"
        val authHeader = Headers.headersOf(
            "X-Emby-Authorization",
            "MediaBrowser Client=\"$CLIENT\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"$version\"",
        )
        val body = """
            {"Username":"$username","Pw":"$password"}
        """.trimIndent()
            .toRequestBody("application/json".toMediaType())
        val request = POST("$baseUrl/Users/authenticatebyname", headers = authHeader, body = body)
        val response = client.newCall(request).execute().body?.string()
        return response?.let { Json.decodeFromString<JsonObject>(it) }
    }

    private fun getVersion(): String? {
        val response = client.newCall(GET("$baseUrl/system/info/public"))
            .execute().body?.string()
        val responseObject = response?.let { Json.decodeFromString<JsonObject>(it) }
        return responseObject?.get("version")?.jsonPrimitive?.contentOrNull
    }

    private fun getRandomString(): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..172)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun saveLogin(key: String, userId: String) {
        preferences.edit()
            .putString(JFConstants.APIKEY_KEY, key)
            .putString(JFConstants.USERID_KEY, userId)
            .apply()
    }

    private fun getPrefDeviceId(): String? = preferences.getString(
        DEVICEID_KEY, null
    )

    private fun setPrefDeviceId(value: String) = preferences.edit().putString(
        DEVICEID_KEY, value
    ).commit()
}

private const val DEVICEID_KEY = "device_id"
private const val CLIENT = "Aniyomi"

package eu.kanade.tachiyomi.animeextension.all.animeonsen

import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class AOAPIInterceptor(client: OkHttpClient) : Interceptor {

    private val token: String

    init {
        token = try {
            val body = """
                {
                    "client_id": "f296be26-28b5-4358-b5a1-6259575e23b7",
                    "client_secret": "349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235",
                    "grant_type": "client_credentials"
                }
            """.trimIndent().toRequestBody("application/json".toMediaType())

            val headers = Headers.headersOf("user-agent", AO_USER_AGENT)

            val tokenResponse = client.newCall(
                POST(
                    "https://auth.animeonsen.xyz/oauth/token",
                    headers,
                    body,
                ),
            ).execute().body.string()

            val tokenObject = Json.decodeFromString<JsonObject>(tokenResponse)

            tokenObject["access_token"]!!.jsonPrimitive.content
        } catch (_: Throwable) {
            ""
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val newRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        return chain.proceed(newRequest)
    }
}

package eu.kanade.tachiyomi.animeextension.en.seez

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class VrfHelper(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    fun decrypt(encrypted: String): String {
        val url = API_URL.newBuilder().apply {
            addPathSegment("fmovies-decrypt")
            addQueryParameter("query", encrypted)
            addQueryParameter("apikey", API_KEY)
        }.build().toString()

        return client.newCall(GET(url)).execute().parseAs<VrfResponse>().url
    }

    fun getVidSrc(query: String, host: String): String {
        val url = API_URL.newBuilder().apply {
            addPathSegment("rawVizcloud")
            addQueryParameter("apikey", API_KEY)
        }.build().toString()

        val futoken = client.newCall(
            GET("https://$host/futoken", headers),
        ).execute().use { it.body.string() }

        val body = FormBody.Builder().apply {
            add("query", query)
            add("futoken", futoken)
        }.build()

        return client.newCall(
            POST(url, body = body),
        ).execute().parseAs<RawResponse>().rawURL
    }

    companion object {
        const val API_KEY = "aniyomi"
        val API_URL = "https://9anime.eltik.net".toHttpUrl()
    }

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { it }): T {
        val responseBody = use { transform(it.body.string()) }
        return json.decodeFromString(responseBody)
    }

    @Serializable
    data class VrfResponse(
        val url: String,
        val vrfQuery: String? = null,
    )

    @Serializable
    data class RawResponse(
        val rawURL: String,
    )
}

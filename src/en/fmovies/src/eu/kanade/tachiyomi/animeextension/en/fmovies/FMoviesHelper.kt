package eu.kanade.tachiyomi.animeextension.en.fmovies

import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class FMoviesHelper(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    private val userAgent = Headers.headersOf(
        "User-Agent",
        "Aniyomi/${AppInfo.getVersionName()} (FMovies; ${BuildConfig.VERSION_CODE})",
    )

    fun getVrf(id: String): String {
        val url = API_URL.newBuilder().apply {
            addPathSegment("fmovies-vrf")
            addQueryParameter("query", id)
            addQueryParameter("apikey", API_KEY)
        }.build().toString()

        return client.newCall(GET(url, userAgent)).execute().parseAs<VrfResponse>().let {
            URLEncoder.encode(it.url, "utf-8")
        }
    }

    fun decrypt(encrypted: String): String {
        val url = API_URL.newBuilder().apply {
            addPathSegment("fmovies-decrypt")
            addQueryParameter("query", encrypted)
            addQueryParameter("apikey", API_KEY)
        }.build().toString()

        return client.newCall(GET(url, userAgent)).execute().parseAs<VrfResponse>().url
    }

    fun getVidSrc(query: String, host: String): String {
        val url = API_URL.newBuilder().apply {
            addPathSegment(if (host.contains("mcloud", true)) "rawMcloud" else "rawVizcloud")
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
            POST(url, body = body, headers = userAgent),
        ).execute().parseAs<RawResponse>().rawURL
    }

    companion object {
        const val API_KEY = "aniyomi"
        val API_URL = "https://9anime.eltik.net".toHttpUrl()
    }
}

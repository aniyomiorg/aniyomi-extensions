package eu.kanade.tachiyomi.animeextension.en.nineanime

import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AniwaveUtils(private val client: OkHttpClient, private val headers: Headers) {

    val json: Json by injectLazy()

    private val userAgent = Headers.headersOf(
        "User-Agent",
        "Aniyomi/${AppInfo.getVersionName()} (AniWave)",
    )

    fun callEnimax(query: String, action: String): String {
        return if (action in listOf("rawVizcloud", "rawMcloud")) {
            val referer = if (action == "rawVizcloud") "https://vidstream.pro/" else "https://mcloud.to/"
            val futoken = client.newCall(
                GET(referer + "futoken", headers),
            ).execute().use { it.body.string() }
            val formBody = FormBody.Builder()
                .add("query", query)
                .add("futoken", futoken)
                .build()
            client.newCall(
                POST(
                    url = "https://9anime.eltik.net/$action?apikey=aniyomi",
                    body = formBody,
                    headers = userAgent,
                ),
            ).execute().parseAs<RawResponse>().rawURL
        } else {
            client.newCall(
                GET("https://9anime.eltik.net/$action?query=$query&apikey=aniyomi", userAgent),
            ).execute().use {
                val body = it.body.string()
                when (action) {
                    "decrypt" -> {
                        json.decodeFromString<VrfResponse>(body).url
                    }
                    else -> {
                        json.decodeFromString<VrfResponse>(body).let { vrf ->
                            "${vrf.vrfQuery}=${java.net.URLEncoder.encode(vrf.url, "utf-8")}"
                        }
                    }
                }
            }
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }
}

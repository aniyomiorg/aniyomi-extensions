package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.util.Base64
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniwaveUtils(private val client: OkHttpClient, private val headers: Headers) {

    val json: Json by injectLazy()

    private val userAgent = Headers.headersOf(
        "User-Agent",
        "Aniyomi/${AppInfo.getVersionName()} (AniWave; ${BuildConfig.VERSION_CODE})",
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
        } else if (action == "decrypt") {
            vrfDecrypt(query)
        } else {
            "vrf=${java.net.URLEncoder.encode(vrfEncrypt(query), "utf-8")}"
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    private fun vrfEncrypt(input: String): String {
        val rc4Key = SecretKeySpec("ysJhV6U27FVIjjuk".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)

        var vrf = cipher.doFinal(input.toByteArray())
        vrf = Base64.encode(vrf, Base64.URL_SAFE or Base64.NO_WRAP)
        vrf = Base64.encode(vrf, Base64.DEFAULT or Base64.NO_WRAP)
        vrf = vrfShift(vrf)
        vrf = Base64.encode(vrf, Base64.DEFAULT)
        vrf = rot13(vrf)

        return vrf.toString(Charsets.UTF_8)
    }

    private fun vrfDecrypt(input: String): String {
        var vrf = input.toByteArray()
        vrf = Base64.decode(vrf, Base64.URL_SAFE)

        val rc4Key = SecretKeySpec("hlPeNwkncH0fq9so".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        vrf = cipher.doFinal(vrf)

        return URLDecoder.decode(vrf.toString(Charsets.UTF_8), "utf-8")
    }

    private fun rot13(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val byte = vrf[i]
            if (byte in 'A'.code..'Z'.code) {
                vrf[i] = ((byte - 'A'.code + 13) % 26 + 'A'.code).toByte()
            } else if (byte in 'a'.code..'z'.code) {
                vrf[i] = ((byte - 'a'.code + 13) % 26 + 'a'.code).toByte()
            }
        }
        return vrf
    }

    private fun vrfShift(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val shift = arrayOf(-3, 3, -4, 2, -2, 5, 4, 5)[i % 8]
            vrf[i] = vrf[i].plus(shift).toByte()
        }
        return vrf
    }
}

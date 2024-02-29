package eu.kanade.tachiyomi.animeextension.en.fmovies

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class FmoviesUtils(private val client: OkHttpClient, private val headers: Headers) {

    // ===================== Media Detail ================================

    private val tmdbURL = "https://api.themoviedb.org/3".toHttpUrl()

    private val seez = "https://seez.su"

    private val apiKey by lazy {
        val jsUrl = client.newCall(GET(seez, headers)).execute().asJsoup()
            .select("script[defer][src]")[1].attr("abs:src")

        val jsBody = client.newCall(GET(jsUrl, headers)).execute().use { it.body.string() }
        Regex("""f="(\w{20,})"""").find(jsBody)!!.groupValues[1]
    }

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/javascript, */*; q=0.01")
        add("Host", "api.themoviedb.org")
        add("Origin", seez)
        add("Referer", "$seez/")
    }.build()

    fun getDetail(mediaTitle: String): TmdbDetailsResponse? =
        runCatching {
            val searchUrl = tmdbURL.newBuilder().apply {
                addPathSegment("search")
                addPathSegment("multi")
                addQueryParameter("query", mediaTitle)
                addQueryParameter("api_key", apiKey)
            }.build().toString()
            val searchResp = client.newCall(GET(searchUrl, headers = apiHeaders))
                .execute()
                .parseAs<TmdbResponse>()

            val media = searchResp.results.first()

            val detailUrl = tmdbURL.newBuilder().apply {
                addPathSegment(media.mediaType)
                addPathSegment(media.id.toString())
                addQueryParameter("api_key", apiKey)
            }.build().toString()
            client.newCall(GET(detailUrl, headers = apiHeaders))
                .execute()
                .parseAs<TmdbDetailsResponse>()
        }.getOrNull()

    // ===================== Encryption ================================
    fun vrfEncrypt(input: String): String {
        val rc4Key = SecretKeySpec("Ij4aiaQXgluXQRs6".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.ENCRYPT_MODE, rc4Key, cipher.parameters)

        var vrf = cipher.doFinal(input.toByteArray())
        vrf = Base64.encode(vrf, Base64.URL_SAFE or Base64.NO_WRAP)
//        vrf = rot13(vrf)
        vrf = Base64.encode(vrf, Base64.URL_SAFE or Base64.NO_WRAP)
        vrf.reverse()
        vrf = Base64.encode(vrf, Base64.URL_SAFE or Base64.NO_WRAP)
        vrf = vrfShift(vrf)
        val stringVrf = vrf.toString(Charsets.UTF_8)
        return java.net.URLEncoder.encode(stringVrf, "utf-8")
    }

    fun vrfDecrypt(input: String): String {
        var vrf = input.toByteArray()
        vrf = Base64.decode(vrf, Base64.URL_SAFE)

        val rc4Key = SecretKeySpec("8z5Ag5wgagfsOuhz".toByteArray(), "RC4")
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
            val shift = arrayOf(4, 3, -2, 5, 2, -4, -4, 2)[i % 8]
            vrf[i] = vrf[i].plus(shift).toByte()
        }
        return vrf
    }
}

@Serializable
data class TmdbResponse(
    val results: List<TmdbResult>,
) {
    @Serializable
    data class TmdbResult(
        val id: Int,
        @SerialName("media_type")
        val mediaType: String = "tv",
    )
}

@Serializable
data class TmdbDetailsResponse(
    val status: String,
    val overview: String? = null,
    @SerialName("next_episode_to_air")
    val nextEpisode: NextEpisode? = null,
) {
    @Serializable
    data class NextEpisode(
        val name: String? = "",
        @SerialName("episode_number")
        val epNumber: Int,
        @SerialName("air_date")
        val airDate: String,
    )
}

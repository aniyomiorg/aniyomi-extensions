package eu.kanade.tachiyomi.animeextension.tr.hentaizm.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class VideaExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val body = client.newCall(GET(url)).execute().body.string()
        val nonce = NONCE_REGEX.find(body)?.groupValues?.elementAt(1) ?: return emptyList()
        val paramL = nonce.substring(0, 32)
        val paramS = nonce.substring(32)
        val result = (0..31).joinToString("") {
            val index = it - (STUPID_KEY.indexOf(paramL.elementAt(it)) - 31)
            paramS.elementAt(index).toString()
        }

        val seed = getRandomString(8)

        val requestUrl = REQUEST_URL.toHttpUrl().newBuilder()
            .addQueryParameter("_s", seed)
            .addQueryParameter("_t", result.substring(0, 16))
            .addQueryParameter("v", url.toHttpUrl().queryParameter("v") ?: "")
            .build()

        val headers = Headers.headersOf("referer", url, "origin", "https://videa.hu")
        val response = client.newCall(GET(requestUrl.toString(), headers)).execute()
        val doc = response.body.string().let {
            when {
                it.startsWith("<?xml") -> Jsoup.parse(it)
                else -> {
                    val key = result.substring(16) + seed + response.headers["x-videa-xs"]
                    val b64dec = Base64.decode(it, Base64.DEFAULT)
                    Jsoup.parse(decryptXml(b64dec, key))
                }
            }
        }

        return doc.select("video_source").mapNotNull {
            val name = it.attr("name")
            val quality = "Videa - $name"
            val hash = doc.selectFirst("hash_value_$name")?.text()
                ?: return@mapNotNull null
            val videoUrl = "https:" + it.text() + "?md5=$hash&expires=${it.attr("exp")}"
            Video(videoUrl, quality, videoUrl, headers)
        }
    }

    private fun decryptXml(xml: ByteArray, key: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.getParameters())
        return cipher.doFinal(xml).toString(Charsets.UTF_8)
    }

    private fun getRandomString(length: Int = 8): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    companion object {
        private val NONCE_REGEX by lazy { Regex("_xt\\s*=\\s*\"([^\"]+)\"") }
        private const val REQUEST_URL = "https://videa.hu/player/xml?platform=desktop"
        private const val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"
    }
}

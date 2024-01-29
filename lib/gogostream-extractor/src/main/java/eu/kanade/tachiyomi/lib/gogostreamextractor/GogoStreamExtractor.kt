package eu.kanade.tachiyomi.lib.gogostreamextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GogoStreamExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client) }

    private fun Element.getBytesAfter(item: String) = className()
        .substringAfter(item)
        .filter(Char::isDigit)
        .toByteArray()

    fun videosFromUrl(serverUrl: String): List<Video> {
        return runCatching {
            val document = client.newCall(GET(serverUrl)).execute().asJsoup()
            val iv = document.selectFirst("div.wrapper")!!.getBytesAfter("container-")
            val secretKey = document.selectFirst("body[class]")!!.getBytesAfter("container-")
            val decryptionKey = document.selectFirst("div.videocontent")!!.getBytesAfter("videocontent-")

            val decryptedAjaxParams = cryptoHandler(
                document.selectFirst("script[data-value]")!!.attr("data-value"),
                iv,
                secretKey,
                encrypt = false,
            ).substringAfter("&")

            val httpUrl = serverUrl.toHttpUrl()
            val host = "https://" + httpUrl.host
            val id = httpUrl.queryParameter("id") ?: throw Exception("error getting id")
            val encryptedId = cryptoHandler(id, iv, secretKey)
            val token = httpUrl.queryParameter("token")
            val qualityPrefix = if (token != null) "Gogostream - " else "Vidstreaming - "

            val jsonResponse = client.newCall(
                GET(
                    "$host/encrypt-ajax.php?id=$encryptedId&$decryptedAjaxParams&alias=$id",
                    Headers.headersOf(
                        "X-Requested-With",
                        "XMLHttpRequest",
                    ),
                ),
            ).execute().body.string()

            val data = json.decodeFromString<EncryptedDataDto>(jsonResponse).data
            val sourceList = cryptoHandler(data, iv, decryptionKey, false)
                .let { json.decodeFromString<DecryptedDataDto>(it) }
                .source

            when {
                sourceList.size == 1 && sourceList.first().type == "hls" -> {
                    val playlistUrl = sourceList.first().file
                    playlistUtils.extractFromHls(playlistUrl, serverUrl, videoNameGen = { qualityPrefix + it })
                }
                else -> {
                    val headers = Headers.headersOf("Referer", serverUrl)
                    sourceList.map { video ->
                        Video(video.file, qualityPrefix + video.label, video.file, headers)
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun cryptoHandler(
        string: String,
        iv: ByteArray,
        secretKeyString: ByteArray,
        encrypt: Boolean = true,
    ): String {
        val ivParameterSpec = IvParameterSpec(iv)
        val secretKey = SecretKeySpec(secretKeyString, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        return if (!encrypt) {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
            String(cipher.doFinal(Base64.decode(string, Base64.DEFAULT)))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec)
            Base64.encodeToString(cipher.doFinal(string.toByteArray()), Base64.NO_WRAP)
        }
    }
}

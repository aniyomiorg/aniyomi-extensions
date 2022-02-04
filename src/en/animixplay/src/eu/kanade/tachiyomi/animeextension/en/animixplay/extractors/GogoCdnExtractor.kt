package eu.kanade.tachiyomi.animeextension.en.animixplay.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.lang.Exception
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@ExperimentalSerializationApi
class GogoCdnExtractor(private val client: OkHttpClient, private val json: Json) {
    fun videosFromUrl(serverUrl: String): List<Video> {
        try {
            val serverResponse = client.newCall(GET(serverUrl)).execute().asJsoup()

            val encrypted = serverResponse.select("script[data-name='crypto']").attr("data-value")
            val iv = serverResponse.select("script[data-name='ts']").attr("data-value").toByteArray()
            val id = serverUrl.toHttpUrl().queryParameter("id") ?: throw Exception("error decrypting")
            val secretKey = cryptoHandler(encrypted, iv, iv + iv, false)

            val encryptedId =
                cryptoHandler(id, "0000000000000000".toByteArray(), secretKey.toByteArray())

            val jsonResponse = client.newCall(
                GET(
                    "http://gogoplay.io/encrypt-ajax.php?id=$encryptedId&time=00000000000000000000",
                    Headers.headersOf("X-Requested-With", "XMLHttpRequest")
                )
            ).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            val autoList = mutableListOf<Video>()
            json.decodeFromString<JsonObject>(jsonResponse)["source"]!!.jsonArray.forEach {
                val label = it.jsonObject["label"].toString().toLowerCase(Locale.ROOT)
                    .trim('"').replace(" ", "")
                val fileURL = it.jsonObject["file"].toString().trim('"')
                val videoHeaders = Headers.headersOf("Referer", serverUrl)
                if (label == "auto") autoList.add(Video(fileURL, label, fileURL, null, videoHeaders))
                else videoList.add(Video(fileURL, label, fileURL, null, videoHeaders))
            }
            return videoList.reversed() + autoList
        } catch (e: Exception) { return emptyList() }
    }

    private fun cryptoHandler(string: String, iv: ByteArray, secretKeyString: ByteArray, encrypt: Boolean = true): String {
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

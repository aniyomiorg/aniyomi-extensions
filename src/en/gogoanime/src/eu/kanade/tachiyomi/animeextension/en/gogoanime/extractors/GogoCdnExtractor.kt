package eu.kanade.tachiyomi.animeextension.en.gogoanime.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val id = serverUrl.toHttpUrl().queryParameter("id") ?: throw Exception("error getting id")
            val iv = "4786443969418267".toByteArray()
            val secretKey = "63976882873536819639922083275907".toByteArray()

            val encryptedId = try { cryptoHandler(id, iv, secretKey) } catch (e: Exception) { e.message ?: "" }

            val jsonResponse = client.newCall(
                GET(
                    "https://gogoplay5.com/encrypt-ajax.php?id=$encryptedId",
                    Headers.headersOf("X-Requested-With", "XMLHttpRequest")
                )
            ).execute().body!!.string()
            val data = json.decodeFromString<JsonObject>(jsonResponse)["data"]!!.jsonPrimitive.content
            val decryptedData = cryptoHandler(data, iv, secretKey, false)
            val videoList = mutableListOf<Video>()
            val autoList = mutableListOf<Video>()
            val array = json.decodeFromString<JsonObject>(decryptedData)["source"]!!.jsonArray
            if (array.size == 1 && array[0].jsonObject["type"]!!.jsonPrimitive.content == "hls") {
                val fileURL = array[0].jsonObject["file"].toString().trim('"')
                val masterPlaylist = client.newCall(GET(fileURL)).execute().body!!.string()
                masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").forEach {
                        val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",NAME").substringBefore("\n") + "p"
                        val videoUrl = fileURL.substringBeforeLast("/") + "/" + it.substringAfter("\n").substringBefore("\n")
                        videoList.add(Video(videoUrl, quality, videoUrl, null))
                    }
            } else array.forEach {
                val label = it.jsonObject["label"].toString().toLowerCase(Locale.ROOT)
                    .trim('"').replace(" ", "")
                val fileURL = it.jsonObject["file"].toString().trim('"')
                val videoHeaders = Headers.headersOf("Referer", serverUrl)
                if (label == "auto") autoList.add(
                    Video(
                        fileURL,
                        label,
                        fileURL,
                        null,
                        videoHeaders
                    )
                )
                else videoList.add(Video(fileURL, label, fileURL, null, videoHeaders))
            }
            return videoList.sortedByDescending { it.quality.substringBefore("p").toInt() } + autoList
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun cryptoHandler(
        string: String,
        iv: ByteArray,
        secretKeyString: ByteArray,
        encrypt: Boolean = true
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

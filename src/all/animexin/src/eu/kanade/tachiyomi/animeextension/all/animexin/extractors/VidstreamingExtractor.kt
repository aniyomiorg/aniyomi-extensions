package eu.kanade.tachiyomi.animeextension.all.animexin.extractors

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
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@ExperimentalSerializationApi
class VidstreamingExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(serverUrl: String, prefix: String): List<Video> {
        try {
            val document = client.newCall(GET(serverUrl)).execute().asJsoup()
            val iv = document.select("div.wrapper")
                .attr("class").substringAfter("container-")
                .filter { it.isDigit() }.toByteArray()
            val secretKey = document.select("body[class]")
                .attr("class").substringAfter("container-")
                .filter { it.isDigit() }.toByteArray()
            val decryptionKey = document.select("div.videocontent")
                .attr("class").substringAfter("videocontent-")
                .filter { it.isDigit() }.toByteArray()
            val encryptAjaxParams = cryptoHandler(
                document.select("script[data-value]")
                    .attr("data-value"),
                iv,
                secretKey,
                false,
            ).substringAfter("&")

            val httpUrl = serverUrl.toHttpUrl()
            val host = "https://" + httpUrl.host + "/"
            val id = httpUrl.queryParameter("id") ?: throw Exception("error getting id")
            val encryptedId = cryptoHandler(id, iv, secretKey)
            val token = httpUrl.queryParameter("token")
            val qualitySuffix = if (token != null) " (Vid-mp4 - Gogostream)" else " (Vid-mp4 - Vidstreaming)"

            val jsonResponse = client.newCall(
                GET(
                    "${host}encrypt-ajax.php?id=$encryptedId&$encryptAjaxParams&alias=$id",
                    Headers.headersOf(
                        "X-Requested-With",
                        "XMLHttpRequest",
                    ),
                ),
            ).execute().body.string()
            val data = json.decodeFromString<JsonObject>(jsonResponse)["data"]!!.jsonPrimitive.content
            val decryptedData = cryptoHandler(data, iv, decryptionKey, false)
            val videoList = mutableListOf<Video>()
            val autoList = mutableListOf<Video>()
            val array = json.decodeFromString<JsonObject>(decryptedData)["source"]!!.jsonArray
            if (array.size == 1 && array[0].jsonObject["type"]!!.jsonPrimitive.content == "hls") {
                val fileURL = array[0].jsonObject["file"].toString().trim('"')
                val masterPlaylist = client.newCall(GET(fileURL)).execute().body.string()
                masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").forEach {
                        val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",").substringBefore("\n") + "p"
                        var videoUrl = it.substringAfter("\n").substringBefore("\n")
                        if (!videoUrl.startsWith("http")) {
                            videoUrl = fileURL.substringBeforeLast("/") + "/$videoUrl"
                        }
                        videoList.add(Video(videoUrl, prefix + quality + qualitySuffix, videoUrl))
                    }
            } else {
                array.forEach {
                    val label = it.jsonObject["label"].toString().lowercase(Locale.ROOT)
                        .trim('"').replace(" ", "")
                    val fileURL = it.jsonObject["file"].toString().trim('"')
                    val videoHeaders = Headers.headersOf("Referer", serverUrl)
                    if (label == "auto") {
                        autoList.add(
                            Video(
                                fileURL,
                                label + qualitySuffix,
                                fileURL,
                                headers = videoHeaders,
                            ),
                        )
                    } else {
                        videoList.add(Video(fileURL, label + qualitySuffix, fileURL, headers = videoHeaders))
                    }
                }
            }
            return videoList.sortedByDescending {
                it.quality.substringBefore(qualitySuffix).substringBefore("p").toIntOrNull() ?: -1
            } + autoList
        } catch (e: Exception) {
            return emptyList()
        }
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

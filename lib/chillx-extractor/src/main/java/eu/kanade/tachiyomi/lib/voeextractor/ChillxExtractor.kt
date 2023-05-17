package eu.kanade.tachiyomi.lib.voeextractor

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.DigestException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class ChillxExtractor(private val client: OkHttpClient, private val mainUrl: String) {

    fun videoFromUrl(url: String): List<Video>? {
        val videoList = mutableListOf<Video>()

        val document = client.newCall(GET(url)).execute().asJsoup().html()
        val master = Regex("""MasterJS\s*=\s*'([^']+)""").find(document)?.groupValues?.get(1)
        val aesJson = Json.decodeFromString<JsonObject>(base64Decode(master ?: return null).toString())
        val encData =
            try {
                AESData(
                    base64Decode(aesJson["ciphertext"]!!.jsonPrimitive.content).toString(),
                    aesJson["iv"]!!.jsonPrimitive.content,
                    aesJson["salt"]!!.jsonPrimitive.content,
                    aesJson["iterations"]!!.jsonPrimitive.content,
                )
            } catch(_: Exception) {
                null
            }

        val decrypt = cryptoAESHandler(encData ?: return null, KEY)
        Log.i("decryptedFile", decrypt)
        val playlistUrl = Regex("""sources:\s*\[\{"file":"([^"]+)""").find(decrypt)?.groupValues?.get(1) ?: return null

        // val token = Regex("""token\s*=\s*"(.+)"""").find(decrypt)?.groupValues?.get(1)
        // val requiredIndex = videoUrl.indexOf(".m3u8?") + 6
        // videoUrl.insert(requiredIndex, "client=6cd79b59e67dd87f4e5603f1c55c6d14&")
        // videoUrl.insert(requiredIndex, "token=$token&")


        val tracks = Regex("""tracks:\s*\[(.+)]""").find(decrypt)?.groupValues?.get(1)
        val trackJson = JSONObject(tracks ?: return null)
        Log.i("tracksJSON", playlistUrl)
        Log.i("tracksJSON", tracks)
        val trackData =
            try {
                SubtitleTrack(
                    trackJson.getString("file"),
                    trackJson.getString("label"),
                    trackJson.getString("kind"),
                )
            } catch(_: Exception) {
                null
            }

        val headers = Headers.headersOf(
            //"accept", "*/*",
            //"accept-encoding", "gzip, deflate, br",
            //"accept-language", "en-US,en;q=0.9",
            //"connection", "keep-alive",
            //"origin", mainUrl,
            //"referer", "$mainUrl/",
            //"sec-ch-ua", "\"Google Chrome\";v=\"113\", \"Chromium\";v=\"113\", \"Not-A.Brand\";v=\"24\"",
            //"sec-ch-ua-mobile", "?0",
            //"sec-ch-ua-platform", "\"Windows\"",
            //"sec-fetch-dest", "empty",
            //"sec-fetch-mode", "cors",
            //"sec-fetch-site", "cross-site",
            "user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36",

        )

        val response = client.newCall(GET(playlistUrl, headers)).execute()
        val masterPlaylist = response.body!!.string()
        Log.i("SDFsaddasSDF", masterPlaylist)
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = it.substringAfter("RESOLUTION=").split(",")[0].split("\n")[0].substringAfter("x") + "p"

                var videoUrl = it.substringAfter("\n").substringBefore("\n")
                if (videoUrl.startsWith("https").not()) {
                    val host = playlistUrl.substringBefore(".m3u8")
                    videoUrl = host + videoUrl
                }
                videoList.add(Video(videoUrl, quality, videoUrl, headers = headers))
            }
        return videoList
    }

    private fun cryptoAESHandler(
        data: AESData,
        pass: String,
    ): String {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(
            pass.toCharArray(),
            data.salt?.hexToByteArray(),
            data.iterations?.toIntOrNull() ?: 1,
            256
        )
        val key = factory.generateSecret(spec)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.encoded, "AES"),
            IvParameterSpec(data.iv?.hexToByteArray())
        )
        return String(cipher.doFinal(data.ciphertext?.toByteArray()))
    }

    private fun base64Decode(string: String): ByteArray {
        return Base64.decode(string, Base64.DEFAULT)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }

            .toByteArray()
    }

    data class AESData(
        val ciphertext: String? = null,
        val iv: String? = null,
        val salt: String? = null,
        val iterations: String? = null,
    )

    data class SubtitleTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
    )

    companion object {
        private const val KEY = "4VqE3#N7zt&HEP^a"
    }
}

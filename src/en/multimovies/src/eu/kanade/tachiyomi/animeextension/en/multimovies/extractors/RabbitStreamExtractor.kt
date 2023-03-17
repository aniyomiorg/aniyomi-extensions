package eu.kanade.tachiyomi.animeextension.en.multimovies.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.security.DigestException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RabbitStreamExtractor(private val client: OkHttpClient) {

    // Prevent (automatic) caching the .JS file for different episodes, because it
    // changes everytime, and a cached old .js will have a invalid AES password,
    // invalidating the decryption algorithm.
    // We cache it manually when initializing the class.
    private val newClient = client.newBuilder()
        .cache(null)
        .build()

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val id = url.toHttpUrl().pathSegments.last()
        val embed = url.toHttpUrl().pathSegments.first()

        val newHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .build()

        val jsonBody = client.newCall(
            GET("https://rabbitstream.net/ajax/$embed/getSources?id=$id", headers = newHeaders),
        ).execute().body.string()
        val parsed = json.decodeFromString<Source>(jsonBody)

        val key = newClient.newCall(
            GET("https://raw.githubusercontent.com/enimax-anime/key/e4/key.txt"),
        ).execute().body.string()

        val decrypted = decrypt(parsed.sources, key) ?: return videoList
        val subtitleList = parsed.tracks.map {
            Track(it.file, it.label)
        }

        val files = json.decodeFromString<List<File>>(decrypted)
        files.forEach {
            val videoHeaders = Headers.headersOf(
                "Accept",
                "*/*",
                "Origin",
                "https://${url.toHttpUrl().host}",
                "Referer",
                "https://${url.toHttpUrl().host}/",
                "User-Agent",
                headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            )

            val masterPlaylist = client.newCall(
                GET(it.file, headers = videoHeaders),
            ).execute().body.string()

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach { res ->
                    val quality = prefix + res.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p "
                    val videoUrl = res.substringAfter("\n").substringBefore("\n")
                    videoList.add(
                        Video(videoUrl, quality, videoUrl, headers = videoHeaders, subtitleTracks = subtitleList),
                    )
                }
        }

        return videoList
    }

    @Serializable
    data class Source(
        val sources: String,
        val tracks: List<Sub>,
    ) {
        @Serializable
        data class Sub(
            val file: String,
            val label: String,
        )
    }

    @Serializable
    data class File(
        val file: String,
    )

    // Stolen from zoro extension
    private fun decrypt(encodedData: String, remoteKey: String): String? {
        val saltedData = Base64.decode(encodedData, Base64.DEFAULT)
        val salt = saltedData.copyOfRange(8, 16)
        val ciphertext = saltedData.copyOfRange(16, saltedData.size)
        val password = remoteKey.toByteArray()
        val (key, iv) = GenerateKeyAndIv(password, salt) ?: return null
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decryptedData = String(cipher.doFinal(ciphertext))
        return decryptedData
    }

    // https://stackoverflow.com/a/41434590/8166854
    private fun GenerateKeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        hashAlgorithm: String = "MD5",
        keyLength: Int = 32,
        ivLength: Int = 16,
        iterations: Int = 1,
    ): List<ByteArray>? {
        val md = MessageDigest.getInstance(hashAlgorithm)
        val digestLength = md.getDigestLength()
        val targetKeySize = keyLength + ivLength
        val requiredLength = (targetKeySize + digestLength - 1) / digestLength * digestLength
        var generatedData = ByteArray(requiredLength)
        var generatedLength = 0

        try {
            md.reset()

            while (generatedLength < targetKeySize) {
                if (generatedLength > 0) {
                    md.update(
                        generatedData,
                        generatedLength - digestLength,
                        digestLength,
                    )
                }

                md.update(password)
                md.update(salt, 0, 8)
                md.digest(generatedData, generatedLength, digestLength)

                for (i in 1 until iterations) {
                    md.update(generatedData, generatedLength, digestLength)
                    md.digest(generatedData, generatedLength, digestLength)
                }

                generatedLength += digestLength
            }
            val result = listOf(
                generatedData.copyOfRange(0, keyLength),
                generatedData.copyOfRange(keyLength, targetKeySize),
            )
            return result
        } catch (e: DigestException) {
            return null
        }
    }
}

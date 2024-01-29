package eu.kanade.tachiyomi.animeextension.en.kisskh

import android.net.Uri
import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SubDecryptor(private val client: OkHttpClient, private val headers: Headers, private val baseurl: String) {
    fun getSubtitles(subUrl: String, subLang: String): Track {
        val subHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/plain, */*")
            add("Origin", baseurl)
            add("Referer", "$baseurl/")
        }.build()

        val subtitleData = client.newCall(
            GET(subUrl, subHeaders),
        ).execute().body.string()

        val chunks = subtitleData.split(CHUNK_REGEX)
            .filter(String::isNotBlank)
            .map(String::trim)

        val decrypted = chunks.mapIndexed { index, chunk ->
            val parts = chunk.split("\n")
            val text = parts.slice(1 until parts.size)
            val d = text.map { decrypt(it) }.joinToString("\n")

            arrayOf(index + 1, parts.first(), d).joinToString("\n")
        }.joinToString("\n\n")

        val file = File.createTempFile("subs", "srt")
            .also(File::deleteOnExit)

        file.writeText(decrypted)
        val uri = Uri.fromFile(file)

        return Track(uri.toString(), subLang)
    }

    companion object {
        private val CHUNK_REGEX by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

        private val KEY = intArrayOf(942683446, 876098358, 875967282, 943142451)
        private val IV = intArrayOf(909653298, 909193779, 925905208, 892483379)
    }

    private fun getKey(words: IntArray): SecretKeySpec {
        val keyBytes = words.toByteArray()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun decrypt(data: String): String {
        val key = getKey(KEY)
        val iv = IvParameterSpec(IV.toByteArray())

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)

        val encryptedBytes = Base64.decode(data, Base64.DEFAULT)
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    private fun IntArray.toByteArray(): ByteArray {
        return ByteArray(size * 4).also { bytes ->
            forEachIndexed { index, value ->
                bytes[index * 4] = (value shr 24).toByte()
                bytes[index * 4 + 1] = (value shr 16).toByte()
                bytes[index * 4 + 2] = (value shr 8).toByte()
                bytes[index * 4 + 3] = value.toByte()
            }
        }
    }
}

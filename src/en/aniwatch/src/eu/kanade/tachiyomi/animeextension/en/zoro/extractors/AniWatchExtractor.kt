package eu.kanade.tachiyomi.animeextension.en.zoro.extractors

import eu.kanade.tachiyomi.animeextension.en.zoro.dto.SourceResponseDto
import eu.kanade.tachiyomi.animeextension.en.zoro.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.en.zoro.dto.VideoLink
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class AniWatchExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()

    companion object {
        private val SERVER_URL = arrayOf("https://megacloud.tv", "https://rapid-cloud.co")
        private val SOURCES_URL = arrayOf("/embed-2/ajax/e-1/getSources?id=", "/ajax/embed-6-v2/getSources?id=")
        private val SOURCES_SPLITTER = arrayOf("/e-1/", "/embed-6-v2/")
        private val SOURCES_KEY = arrayOf("1", "6")
    }

    private fun cipherTextCleaner(data: String, type: String): Pair<String, String> {
        // TODO: fetch the key only when needed, using a thread-safe map
        // (Like ConcurrentMap?) or MUTEX hacks.
        val indexPairs = client.newCall(GET("https://raw.githubusercontent.com/Claudemirovsky/keys/e$type/key"))
            .execute()
            .use { it.body.string() }
            .let { json.decodeFromString<List<List<Int>>>(it) }

        val (password, ciphertext, _) = indexPairs.fold(Triple("", data, 0)) { previous, item ->
            val start = item.first() + previous.third
            val end = start + item.last()
            val passSubstr = data.substring(start, end)
            val passPart = previous.first + passSubstr
            val cipherPart = previous.second.replace(passSubstr, "")
            Triple(passPart, cipherPart, previous.third + item.last())
        }

        return Pair(ciphertext, password)
    }

    private fun tryDecrypting(ciphered: String, type: String, attempts: Int = 0): String {
        if (attempts > 2) throw Exception("PLEASE NUKE ANIWATCH AND CLOUDFLARE")
        val (ciphertext, password) = cipherTextCleaner(ciphered, type)
        return CryptoAES.decrypt(ciphertext, password).ifEmpty {
            tryDecrypting(ciphered, type, attempts + 1)
        }
    }

    fun getVideoDto(url: String): VideoDto {
        val type = if (url.startsWith("https://megacloud.tv")) 0 else 1
        val keyType = SOURCES_KEY[type]

        val id = url.substringAfter(SOURCES_SPLITTER[type], "")
            .substringBefore("?", "").ifEmpty { throw Exception("I HATE THE ANTICHRIST") }
        val srcRes = client.newCall(GET(SERVER_URL[type] + SOURCES_URL[type] + id))
            .execute()
            .use { it.body.string() }

        val data = json.decodeFromString<SourceResponseDto>(srcRes)
        if (!data.encrypted) return json.decodeFromString<VideoDto>(srcRes)

        val ciphered = data.sources.jsonPrimitive.content.toString()
        val decrypted = json.decodeFromString<List<VideoLink>>(tryDecrypting(ciphered, keyType))
        return VideoDto(decrypted, data.tracks)
    }
}

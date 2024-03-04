package eu.kanade.tachiyomi.multisrc.dopeflix.extractors

import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.SourceResponseDto
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.VideoDto
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.VideoLink
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class DopeFlixExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()

    companion object {
        private const val SOURCES_PATH = "/ajax/embed-4/getSources?id="
        private const val SCRIPT_URL = "https://rabbitstream.net/js/player/prod/e4-player.min.js"
        private val MUTEX = Mutex()
        private var realIndexPairs: List<List<Int>> = emptyList()

        private fun <R> runLocked(block: () -> R) = runBlocking(Dispatchers.IO) {
            MUTEX.withLock { block() }
        }
    }

    private fun generateIndexPairs(): List<List<Int>> {
        val script = client.newCall(GET(SCRIPT_URL)).execute().body.string()
        return script.substringAfter("const ")
            .substringBefore("()")
            .substringBeforeLast(",")
            .split(",")
            .map {
                val value = it.substringAfter("=")
                when {
                    value.contains("0x") -> value.substringAfter("0x").toInt(16)
                    else -> value.toInt()
                }
            }
            .drop(1)
            .chunked(2)
            .map(List<Int>::reversed) // just to look more like the original script
    }

    private fun cipherTextCleaner(data: String): Pair<String, String> {
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

    private val mutex = Mutex()

    private var indexPairs: List<List<Int>>
        get() {
            return runLocked {
                if (realIndexPairs.isEmpty()) {
                    realIndexPairs = generateIndexPairs()
                }
                realIndexPairs
            }
        }
        set(value) {
            runLocked {
                if (realIndexPairs.isNotEmpty()) {
                    realIndexPairs = value
                }
            }
        }

    private fun tryDecrypting(ciphered: String, attempts: Int = 0): String {
        if (attempts > 2) throw Exception("PLEASE NUKE DOPEBOX AND SFLIX")
        val (ciphertext, password) = cipherTextCleaner(ciphered)
        return CryptoAES.decrypt(ciphertext, password).ifEmpty {
            indexPairs = emptyList() // force re-creation
            tryDecrypting(ciphered, attempts + 1)
        }
    }

    fun getVideoDto(url: String): VideoDto {
        val id = url.substringAfter("/embed-4/", "")
            .substringBefore("?", "").ifEmpty { throw Exception("I HATE THE ANTICHRIST") }
        val serverUrl = url.substringBefore("/embed")
        val srcRes = client.newCall(
            GET(
                serverUrl + SOURCES_PATH + id,
                headers = Headers.headersOf("x-requested-with", "XMLHttpRequest"),
            ),
        )
            .execute()
            .body.string()

        val data = json.decodeFromString<SourceResponseDto>(srcRes)
        if (!data.encrypted) return json.decodeFromString<VideoDto>(srcRes)

        val ciphered = data.sources.jsonPrimitive.content.toString()
        val decrypted = json.decodeFromString<List<VideoLink>>(tryDecrypting(ciphered))
        return VideoDto(decrypted, data.tracks)
    }
}

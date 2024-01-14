package eu.kanade.tachiyomi.lib.megacloudextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class MegaCloudExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val cacheControl = CacheControl.Builder().noStore().build()
    private val noCacheClient = client.newBuilder()
        .cache(null)
        .build()

    companion object {
        private val SERVER_URL = arrayOf("https://megacloud.tv", "https://rapid-cloud.co")
        private val SOURCES_URL = arrayOf("/embed-2/ajax/e-1/getSources?id=", "/ajax/embed-6-v2/getSources?id=")
        private val SOURCES_SPLITTER = arrayOf("/e-1/", "/embed-6-v2/")
        private val SOURCES_KEY = arrayOf("1", "6")
        private const val E1_SCRIPT_URL = "https://megacloud.tv/js/player/a/prod/e1-player.min.js"
        private const val E6_SCRIPT_URL = "https://rapid-cloud.co/js/player/prod/e6-player-v2.min.js"
        private const val E4_SCRIPT_URL = "https://rabbitstream.net/js/player/prod/e4-player.min.js"
        private val INDEX_PAIRS_MAP = mutableMapOf("1" to emptyList<List<Int>>(), "6" to emptyList<List<Int>>())
        private val MUTEX = Mutex()

        private inline fun <reified R> runLocked(crossinline block: () -> R) = runBlocking(Dispatchers.IO) {
            MUTEX.withLock { block() }
        }
    }

    private fun getIndexPairs(type: String) = runLocked {
        INDEX_PAIRS_MAP[type].orEmpty().ifEmpty {
            val scriptUrl = when (type) {
                "1" -> E1_SCRIPT_URL
                "6" -> E6_SCRIPT_URL
                "4" -> E4_SCRIPT_URL
                else -> throw Exception("Unknown key type")
            }
            val script = noCacheClient.newCall(GET(scriptUrl, cache = cacheControl))
                .execute()
                .use { it.body.string() }
            val regex =
                Regex("case\\s*0x[0-9a-f]+:(?![^;]*=partKey)\\s*\\w+\\s*=\\s*(\\w+)\\s*,\\s*\\w+\\s*=\\s*(\\w+);")
            val matches = regex.findAll(script).toList()
            val indexPairs = matches.map { match ->
                val var1 = match.groupValues[1]
                val var2 = match.groupValues[2]

                val regexVar1 = Regex(",${var1}=((?:0x)?([0-9a-fA-F]+))")
                val regexVar2 = Regex(",${var2}=((?:0x)?([0-9a-fA-F]+))")

                val matchVar1 = regexVar1.find(script)?.groupValues?.get(1)?.removePrefix("0x")
                val matchVar2 = regexVar2.find(script)?.groupValues?.get(1)?.removePrefix("0x")

                if (matchVar1 != null && matchVar2 != null) {
                    try {
                        listOf(matchVar1.toInt(16), matchVar2.toInt(16))
                    } catch (e: NumberFormatException) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }.filter { it.isNotEmpty() }
            INDEX_PAIRS_MAP[type] = indexPairs
            indexPairs
        }
    }

    private fun cipherTextCleaner(data: String, type: String): Pair<String, String> {
        val indexPairs = getIndexPairs(type)
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
            // Update index pairs
            runLocked { INDEX_PAIRS_MAP[type] = emptyList<List<Int>>() }
            tryDecrypting(ciphered, type, attempts + 1)
        }
    }

    fun getVideosFromUrl(url: String, type: String, name: String): List<Video> {
        val video = getVideoDto(url)

        val masterUrl = video.sources.first().file
        val subs2 = video.tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()
        return playlistUtils.extractFromHls(
            masterUrl,
            videoNameGen = { "$name - $it - $type" },
            subtitleList = subs2,
            referer = "https://${url.toHttpUrl().host}/"
        )
    }

    private fun getVideoDto(url: String): VideoDto {
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


    @Serializable
    data class VideoDto(
        val sources: List<VideoLink>,
        val tracks: List<TrackDto>? = null,
    )

    @Serializable
    data class SourceResponseDto(
        val sources: JsonElement,
        val encrypted: Boolean = true,
        val tracks: List<TrackDto>? = null,
    )

    @Serializable
    data class VideoLink(val file: String = "")

    @Serializable
    data class TrackDto(val file: String, val kind: String, val label: String = "")
}

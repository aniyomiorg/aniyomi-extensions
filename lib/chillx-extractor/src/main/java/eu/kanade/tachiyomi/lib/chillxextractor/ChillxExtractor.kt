package eu.kanade.tachiyomi.lib.chillxextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decryptWithSalt
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class ChillxExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private const val KEY = "m4H6D9%0${'$'}N&F6rQ&"

        private val REGEX_MASTER_JS by lazy { Regex("""MasterJS\s*=\s*'([^']+)""") }
        private val REGEX_SOURCES by lazy { Regex("""sources:\s*\[\{"file":"([^"]+)""") }
        private val REGEX_FILE by lazy { Regex("""file: ?"([^"]+)"""") }

        // matches "[language]https://...,"
        private val REGEX_SUBS by lazy { Regex("""\[(.*?)\](.*?)"?\,""") }
    }

    fun videoFromUrl(url: String, referer: String, prefix: String = "Chillx - "): List<Video> {
        val body = client.newCall(GET(url, Headers.headersOf("Referer", "$referer/")))
            .execute()
            .use { it.body.string() }

        val master = REGEX_MASTER_JS.find(body)?.groupValues?.get(1) ?: return emptyList()
        val aesJson = json.decodeFromString<CryptoInfo>(master)
        val decryptedScript = decryptWithSalt(aesJson.ciphertext, aesJson.salt, KEY)
            .replace("\\n", "\n")
            .replace("\\", "")

        val masterUrl = REGEX_SOURCES.find(decryptedScript)?.groupValues?.get(1)
            ?: REGEX_FILE.find(decryptedScript)?.groupValues?.get(1)
            ?: return emptyList()

        val subtitleList = buildList<Track> {
            decryptedScript.takeIf { it.contains("subtitle:") }
                ?.substringAfter("subtitle: ")
                ?.substringBefore("\n")
                ?.let(REGEX_SUBS::findAll)
                ?.forEach { add(Track(it.groupValues[2], it.groupValues[1])) }

            decryptedScript.takeIf { it.contains("tracks:") }
                ?.substringAfter("tracks: ")
                ?.substringBefore("\n")
                ?.also {
                    runCatching {
                        json.decodeFromString<List<TrackDto>>(it)
                            .filter { it.kind == "captions" }
                            .forEach { add(Track(it.file, it.label)) }
                    }
                }
        }

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = url,
            videoNameGen = { "$prefix$it" },
            subtitleList = subtitleList,
        )
    }

    @Serializable
    data class CryptoInfo(
        @SerialName("ct")
        val ciphertext: String,
        @SerialName("s")
        val salt: String,
    )

    @Serializable
    data class TrackDto(
        val kind: String,
        val label: String = "",
        val file: String,
    )
}

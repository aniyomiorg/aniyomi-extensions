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
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class ChillxExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private val REGEX_MASTER_JS by lazy { Regex("""JScript[\w+]?\s*=\s*'([^']+)""") }
        private val REGEX_EVAL_KEY by lazy { Regex("""eval\(\S+\("(\S+)",\d+,"(\S+)",(\d+),(\d+),""") }
        private val REGEX_SOURCES by lazy { Regex("""sources:\s*\[\{"file":"([^"]+)""") }
        private val REGEX_FILE by lazy { Regex("""file: ?"([^"]+)"""") }
        private val REGEX_SOURCE by lazy { Regex("""source = ?"([^"]+)"""") }

        // matches "[language]https://...,"
        private val REGEX_SUBS by lazy { Regex("""\[(.*?)\](.*?)"?\,""") }
    }

    fun videoFromUrl(url: String, referer: String, prefix: String = "Chillx - "): List<Video> {
        val newHeaders = headers.newBuilder()
            .set("Referer", "$referer/")
            .set("Accept-Language", "en-US,en;q=0.5")
            .build()

        val body = client.newCall(GET(url, newHeaders)).execute().body.string()

        val master = REGEX_MASTER_JS.find(body)?.groupValues?.get(1) ?: return emptyList()
        val aesJson = json.decodeFromString<CryptoInfo>(master)
        val key = getKey(body)
        val decryptedScript = decryptWithSalt(aesJson.ciphertext, aesJson.salt, key)
            .replace("\\n", "\n")
            .replace("\\", "")

        val masterUrl = REGEX_SOURCES.find(decryptedScript)?.groupValues?.get(1)
            ?: REGEX_FILE.find(decryptedScript)?.groupValues?.get(1)
            ?: REGEX_SOURCE.find(decryptedScript)?.groupValues?.get(1)
            ?: return emptyList()

        val subtitleList = buildList<Track> {
            body.takeIf { it.contains("<track kind=\"captions\"") }
                ?.let(Jsoup::parse)
                ?.select("track[kind=captions]")
                ?.forEach {
                    add(Track(it.attr("src"), it.attr("label")))
                }

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

    private fun getKey(body: String): String {
        val (encrypted, pass, offset, index) = REGEX_EVAL_KEY.find(body)!!.groupValues.drop(1)
        val decrypted = decryptScript(encrypted, pass, offset.toInt(), index.toInt())
        return decrypted.substringAfter("'").substringBefore("'")
    }

    private fun decryptScript(encrypted: String, pass: String, offset: Int, index: Int): String {
        val trimmedPass = pass.substring(0, index)
        val bits = encrypted.split(pass[index]).map { item ->
            trimmedPass.foldIndexed(item) { index, acc, it ->
                acc.replace(it.toString(), index.toString())
            }
        }.filter(String::isNotBlank)

        return bits.joinToString("") { Char(it.toInt(index) - offset).toString() }
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

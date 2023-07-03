package eu.kanade.tachiyomi.lib.gdriveplayerextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decryptWithSalt
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class GdrivePlayerExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, name: String, headers: Headers): List<Video> {
        val newUrl = url.replace(".us", ".to").replace(".me", ".to")
        val body = client.newCall(GET(newUrl, headers = headers)).execute()
            .body.string()

        val subtitleList = Jsoup.parse(body).selectFirst("div:contains(\\.srt)")
            ?.let { element ->
                val subUrl = "https://gdriveplayer.to/?subtitle=" + element.text()
                listOf(Track(subUrl, "Subtitles"))
            } ?: emptyList()

        val eval = Unpacker.unpack(body).replace("\\", "")
        val json = Json.decodeFromString<JsonObject>(REGEX_DATAJSON.getFirst(eval))
        val sojson = REGEX_SOJSON.getFirst(eval)
            .split(Regex("\\D+"))
            .joinToString("") {
                Char(it.toInt()).toString()
            }
        val password = REGEX_PASSWORD.getFirst(sojson)
        val decrypted = decryptAES(password, json) ?: return emptyList()

        val secondEval = Unpacker.unpack(decrypted).replace("\\", "")
        return REGEX_VIDEOURL.findAll(secondEval)
            .distinctBy { it.groupValues[2] } // remove duplicates by quality
            .map {
                val qualityStr = it.groupValues[2]
                val quality = "$playerName ${qualityStr}p - $name"
                val videoUrl = "https:" + it.groupValues[1] + "&res=$qualityStr"
                Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleList)
            }.toList()
    }

    private fun decryptAES(password: String, json: JsonObject): String? {
        val salt = json["s"]!!.jsonPrimitive.content
        val ciphertext = json["ct"]!!.jsonPrimitive.content
        return decryptWithSalt(ciphertext, salt, password)
    }

    private fun Regex.getFirst(item: String): String {
        return find(item)?.groups?.elementAt(1)?.value!!
    }

    companion object {
        private const val playerName = "GDRIVE"

        private val REGEX_DATAJSON = Regex("data=\"(\\S+?)\";")
        private val REGEX_PASSWORD = Regex("var pass = \"(\\S+?)\"")
        private val REGEX_SOJSON = Regex("null,['|\"](\\w+)['|\"]")
        private val REGEX_VIDEOURL = Regex("file\":\"(\\S+?)\".*?res=(\\d+)")
    }
}

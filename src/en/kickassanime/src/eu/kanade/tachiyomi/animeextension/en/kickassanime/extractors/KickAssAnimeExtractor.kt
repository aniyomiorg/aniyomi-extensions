package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.VideoDto
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decodeHex
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class KickAssAnimeExtractor(private val client: OkHttpClient, private val json: Json) {
    fun videosFromUrl(url: String): List<Video> {
        val idQuery = url.substringAfterLast("?")
        val baseUrl = url.substringBeforeLast("/") // baseUrl + endpoint/player
        val response = client.newCall(GET("$baseUrl/source.php?$idQuery")).execute()
            .body.string()

        val (encryptedData, ivhex) = response.substringAfter(":\"")
            .substringBefore('"')
            .replace("\\", "")
            .split(":")

        // TODO: Create something to get the key dynamically.
        // Maybe we can do something like what is being used at Dopebox, Sflix and Zoro:
        // Leave the hard work to github actions and make the extension just fetch the key
        // from the repository.
        val key = "7191d608bd4deb4dc36f656c4bbca1b7".toByteArray()
        val iv = ivhex.decodeHex()

        val videoObject = try {
            val decrypted = CryptoAES.decrypt(encryptedData, key, iv)
            json.decodeFromString<VideoDto>(decrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        val masterPlaylist = client.newCall(GET(videoObject.playlistUrl)).execute()
            .body.string()

        val separator = "#EXT-X-STREAM-INF"
        return masterPlaylist.substringAfter(separator).split(separator).map {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringBefore("\n")
                .substringAfter("x")
                .substringBefore(",") + "p"

            val quality = when {
                "pink" in url -> "PinkBird $resolution"
                else -> "SapphireDuck $resolution"
            }
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, quality, videoUrl)
        }
    }
}

package eu.kanade.tachiyomi.animeextension.en.dramacool.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class StreamSBExtractor(private val client: OkHttpClient) {

    private val hexArray = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF

            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val id = url.substringAfter("e/").substringBefore("?")
        val bytes = id.toByteArray()
        val bytesToHex = bytesToHex(bytes)
        val master = "https://sbplay2.com/sourcesx38/7361696b6f757c7c${bytesToHex}7c7c7361696b6f757c7c73747265616d7362/7361696b6f757c7c363136653639366436343663363136653639366436343663376337633631366536393664363436633631366536393664363436633763376336313665363936643634366336313665363936643634366337633763373337343732363536313664373336327c7c7361696b6f757c7c73747265616d7362"
        val json = Json.decodeFromString<JsonObject>(Jsoup.connect(master).ignoreContentType(true).header("watchsb", "streamsb").execute().body())
        val masterUrl = json["stream_data"]!!.jsonObject["file"].toString().trim('"')
        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
        val videoList = mutableListOf<Video>()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            val quality = "StreamSB:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            videoList.add(Video(videoUrl, quality, videoUrl, null))
        }
        return videoList
    }
}

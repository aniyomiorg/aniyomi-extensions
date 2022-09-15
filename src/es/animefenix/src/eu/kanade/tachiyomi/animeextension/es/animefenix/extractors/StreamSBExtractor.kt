package eu.kanade.tachiyomi.animeextension.es.animefenix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient

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

    fun videosFromUrl(url: String, headers: Headers, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        return try {
            val sbUrl = url.substringBefore("/e/")
            val id = url.substringAfter("/e/").substringBefore(".html")
            val bytes = id.toByteArray()
            val bytesToHex = bytesToHex(bytes)
            val master =
                "$sbUrl/sources43/416f794d637048744d4565577c7c${bytesToHex}7c7c776e6c7a365964385a484b767c7c73747265616d7362/656d5a62394f713230524a667c7c373635353537333734623561373634613330353134633631376337633339353037343631363934393335363434333730373633363763376337613737353836323434353534363431343633323533376137633763373337343732363536313664373336327c7c59304b7778506d424c4c32767c7c73747265616d7362"
            val json = Json.decodeFromString<JsonObject>(
                client.newCall(GET(master, headers))
                    .execute().body!!.string()
            )
            val masterUrl = json["stream_data"]!!.jsonObject["file"].toString().trim('"')
            val masterPlaylist = client.newCall(GET(masterUrl, headers)).execute().body!!.string()

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = prefix + "StreamSB:" + it.substringAfter("RESOLUTION=").substringAfter("x")
                    .substringBefore(",") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                videoList.add(Video(videoUrl, quality, videoUrl, headers = headers))
            }
            videoList
        } catch (e: Exception) {
            videoList
        }
    }
}

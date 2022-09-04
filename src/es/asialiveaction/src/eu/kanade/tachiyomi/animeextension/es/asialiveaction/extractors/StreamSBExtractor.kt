package eu.kanade.tachiyomi.animeextension.es.asialiveaction.extractors

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
            val master = "$sbUrl/sources43/674a44656e7654507975614b7c7c${bytesToHex}7c7c4a6d665478704f786e5a464f7c7c73747265616d7362/384c6d46545332726b3171787c7c373637333438343737393661363735343538366434353730376337633734353037393332353734333638363436643730363637373763376336363631346634353732366333323635343933343537346637633763373337343732363536313664373336327c7c5154416f774c34306d4877387c7c73747265616d7362"
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

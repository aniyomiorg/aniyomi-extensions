package eu.kanade.tachiyomi.animeextension.de.kinoking.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient

@ExperimentalSerializationApi
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

    fun videosFromUrl(url: String, headers: Headers, lang: String): List<Video> {
        try {
            val sbHeaders = headers.newBuilder()
                .set("Referer", url)
                .set(
                    "User-Agent",
                    "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0"
                )
                .set("Accept-Language", "en-US,en;q=0.5")
                .set("watchsb", "streamsb")
                .build()
            val sbUrl = url.substringBefore("/e")
            val id = url.substringAfter("e/").substringBefore(".html")
            val bytes = id.toByteArray()
            val bytesToHex = bytesToHex(bytes)
            val master = "$sbUrl/sources43/7a6b4d4d6970595145434f707c7c${bytesToHex}7c7c795651787857676c463134447c7c73747265616d7362/7373566465444f45326b64487c7c363536663634333034353633363935383338343437373664376337633339343137393739366334623335366537613736363834313763376334643431366336653663363737353333376134323537353737633763373337343732363536313664373336327c7c713168506f467931717763437c7c73747265616d7362"
            val json = Json.decodeFromString<JsonObject>(
                client.newCall(GET(master, sbHeaders))
                    .execute().body!!.string()
            )
            val masterUrl = json["stream_data"]!!.jsonObject["file"].toString().trim('"')
            val masterPlaylist = client.newCall(GET(masterUrl, sbHeaders)).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = "StreamSB: " + it.substringAfter("RESOLUTION=").substringAfter("x")
                        .substringBefore(",") + "p " + lang
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")
                    videoList.add(Video(videoUrl, quality, videoUrl, headers = sbHeaders))
                }
            return videoList.reversed()
        } catch (e: Exception) {
            return emptyList()
        }
    }
}

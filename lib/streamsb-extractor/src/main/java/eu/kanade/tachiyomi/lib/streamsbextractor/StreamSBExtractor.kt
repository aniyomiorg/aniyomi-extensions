package eu.kanade.tachiyomi.lib.streamsbextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class StreamSBExtractor(private val client: OkHttpClient) {

    protected fun bytesToHex(bytes: ByteArray): String {
        val hexArray = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF

            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    // animension, asianload and dramacool uses "common = false"
    private fun fixUrl(url: String, common: Boolean): String {
        val host = url.toHttpUrl().host
        val sbUrl = "https://$host/sources15"
        val id = url.substringAfter(host)
            .substringAfter("/e/")
            .substringAfter("/embed-")
            .substringBefore("?")
            .substringBefore(".html")
            .substringAfter("/")
        return sbUrl + if (common) {
            val hexBytes = bytesToHex(id.toByteArray())
            "/625a364258615242766475327c7c${hexBytes}7c7c4761574550654f7461566d347c7c73747265616d7362"
        } else {
            "/${bytesToHex("||$id||||streamsb".toByteArray())}/"
        }
    }

    fun videosFromUrl(url: String, headers: Headers, prefix: String = "", suffix: String = "", common: Boolean = true, manualData: Boolean = false): List<Video> {
        val newHeaders = if(manualData) headers else headers.newBuilder()
            .set("referer", url)
            .set("watchsb", "sbstream")
            .set("authority", "embedsb.com")
            .build()
        return try {
            val master = if(manualData) url else fixUrl(url, common)
            val json = Json { ignoreUnknownKeys = true }.decodeFromString<Response>(
                client.newCall(GET(master, newHeaders))
                    .execute()
                    .use { it.body.string() }
            )
            val masterUrl = json.stream_data.file.trim('"')
            val subtitleList = json.stream_data.subs?.let {
                it.map { s -> Track(s.file, s.label) }
            } ?: emptyList()

            val masterPlaylist = client.newCall(GET(masterUrl, newHeaders))
                .execute()
                .use { it.body.string() }

            val audioRegex = Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""")
            val audioList: List<Track> = audioRegex.findAll(masterPlaylist)
                .map {
                    Track(
                        it.groupValues[2], // Url
                        it.groupValues[1] // Name
                    )
                }.toList()

            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val resolution = it.substringAfter("RESOLUTION=")
                    .substringBefore("\n")
                    .substringAfter("x")
                    .substringBefore(",") + "p"
                val quality = ("StreamSB:" + resolution).let {
                    if(prefix.isNotBlank()) "$prefix $it"
                    else it
                }.let {
                    if(suffix.isNotBlank()) "$it $suffix"
                    else it
                }
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                if (audioList.isEmpty()) {
                    Video(videoUrl, quality, videoUrl, headers = newHeaders, subtitleTracks = subtitleList)
                } else {
                    Video(videoUrl, quality, videoUrl, headers = newHeaders, subtitleTracks = subtitleList, audioTracks = audioList)
                }
            }
        } catch (e: Exception) {
            emptyList<Video>()
        }
    }

    fun videosFromDecryptedUrl(realUrl: String, headers: Headers, prefix: String = "", suffix: String = ""): List<Video> {
        return videosFromUrl(realUrl, headers, prefix, suffix, manualData = true)
    }
}

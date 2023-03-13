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
        val sbUrl = "https://${url.toHttpUrl().host}"
        val id = url.substringAfter("${url.toHttpUrl().host}")
            .substringAfter("/e/")
            .substringAfter("/embed-")
            .substringBefore("?")
            .substringBefore(".html")
            .substringAfter("/")
        return if (common) {
            val hexBytes = bytesToHex(id.toByteArray())
            "$sbUrl/sources15/625a364258615242766475327c7c${hexBytes}7c7c4761574550654f7461566d347c7c73747265616d7362"
        } else {
            "$sbUrl/sources15/${bytesToHex("||$id||||streamsb".toByteArray())}/"
        }
    }

    fun videosFromUrl(url: String, headers: Headers, prefix: String = "", suffix: String = "", common: Boolean = true): List<Video> {
        val newHeaders = headers.newBuilder()
            .set("referer", url)
            .set("watchsb", "sbstream")
            .set("authority", "embedsb.com")
            .build()
        return try {
            val master = fixUrl(url, common)
            val json = Json { ignoreUnknownKeys = true }.decodeFromString<Response>(
                client.newCall(GET(master, newHeaders))
                    .execute().body.string()
            )
            val masterUrl = json.stream_data.file.trim('"')
            val subtitleList = json.stream_data.subs?.let {
                it.map { s -> Track(s.file, s.label) }
            } ?: emptyList()

            val masterPlaylist = client.newCall(GET(masterUrl, newHeaders))
                .execute()
                .body.string()

            val audioList = mutableListOf<Track>()
            val audioRegex = Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""")
            audioList.addAll(
                audioRegex.findAll(masterPlaylist).map {
                    Track(
                        it.groupValues[2],
                        it.groupValues[1]
                    )
                }
            )

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
        return try {
            val json = Json { ignoreUnknownKeys = true }.decodeFromString<Response>(client.newCall(GET(realUrl, headers)).execute().body.string())
            val masterUrl = json.stream_data.file.trim('"')
            val subtitleList = json.stream_data.subs?.let {
                it.map { s -> Track(s.file, s.label) }
            } ?: emptyList()

            val masterPlaylist = client.newCall(GET(masterUrl, headers)).execute().body.string()

            val audioList = mutableListOf<Track>()
            val audioRegex = Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""")
            audioList.addAll(
                audioRegex.findAll(masterPlaylist).map {
                    Track(
                        it.groupValues[2],
                        it.groupValues[1]
                    )
                }
            )

            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val resolution = it.substringAfter("RESOLUTION=")
                    .substringBefore("\n")
                    .substringAfter("x")
                    .substringBefore(",") + "p"
                val quality = ("StreamSB:$resolution").let {
                    if(prefix.isNotBlank()) "$prefix $it"
                    else it
                }.let {
                    if(suffix.isNotBlank()) "$it $suffix"
                    else it
                }
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                if (audioList.isEmpty()) {
                    Video(videoUrl, quality, videoUrl, headers = headers, subtitleTracks = subtitleList)
                } else {
                    Video(videoUrl, quality, videoUrl, headers = headers, subtitleTracks = subtitleList, audioTracks = audioList)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package eu.kanade.tachiyomi.lib.streamsbextractor

import android.app.Application
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class StreamSBExtractor(private val client: OkHttpClient) {

    companion object {
        private const val PREF_ENDPOINT_KEY = "streamsb_api_endpoint"
        private const val PREF_ENDPOINT_DEFAULT = "/sources16"
        private const val ENDPOINT_URL = "https://raw.githubusercontent.com/Claudemirovsky/streamsb-endpoint/master/endpoint.txt"
    }

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences(javaClass.simpleName, 0x0000)
    }

    private fun getEndpoint() = preferences.getString(PREF_ENDPOINT_KEY, PREF_ENDPOINT_DEFAULT)!!

    private fun updateEndpoint() {
        client.newCall(GET(ENDPOINT_URL)).execute()
            .use { it.body.string() }
            .let {
                preferences.edit().putString(PREF_ENDPOINT_KEY, it).commit()
            }
    }

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
        val sbUrl = "https://$host" + getEndpoint()
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

    fun videosFromUrl(
        url: String,
        headers: Headers,
        prefix: String = "",
        suffix: String = "",
        common: Boolean = true,
        manualData: Boolean = false,
    ): List<Video> {
        val trimmedUrl = url.trim() // Prevents some crashes
        val newHeaders = if (manualData) {
            headers
        } else {
            headers.newBuilder()
                .set("referer", trimmedUrl)
                .set("watchsb", "sbstream")
                .set("authority", "embedsb.com")
                .build()
        }
        return runCatching {
            val master = if (manualData) trimmedUrl else fixUrl(trimmedUrl, common)
            val request = client.newCall(GET(master, newHeaders)).execute()

            val json = json.decodeFromString<Response>(
                if (request.code == 200) {
                    request.use { it.body.string() }
                } else {
                    request.close()
                    updateEndpoint()
                    client.newCall(GET(fixUrl(trimmedUrl, common), newHeaders))
                        .execute()
                        .use { it.body.string() }
                },
            )

            val masterUrl = json.stream_data.file.trim('"')
            val subtitleList = json.stream_data.subs
                ?.map { Track(it.file, it.label) }
                ?: emptyList()

            val masterPlaylist = client.newCall(GET(masterUrl, newHeaders))
                .execute()
                .use { it.body.string() }

            val audioRegex = Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""")
            val audioList: List<Track> = audioRegex.findAll(masterPlaylist)
                .map {
                    Track(
                        it.groupValues[2], // Url
                        it.groupValues[1], // Name
                    )
                }.toList()

            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val resolution = it.substringAfter("RESOLUTION=")
                    .substringBefore("\n")
                    .substringAfter("x")
                    .substringBefore(",") + "p"
                val quality = ("StreamSB:" + resolution).let {
                    buildString {
                        if (prefix.isNotBlank()) append("$prefix ")
                        append(it)
                        if (prefix.isNotBlank()) append(" $suffix")
                    }
                }
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, headers = newHeaders, subtitleTracks = subtitleList, audioTracks = audioList)
            }
        }.getOrNull() ?: emptyList<Video>()
    }

    fun videosFromDecryptedUrl(realUrl: String, headers: Headers, prefix: String = "", suffix: String = ""): List<Video> {
        return videosFromUrl(realUrl, headers, prefix, suffix, manualData = true)
    }
}

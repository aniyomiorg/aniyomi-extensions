package eu.kanade.tachiyomi.animeextension.pt.rinecloud.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class RineCloudExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val playerDoc = client.newCall(GET(url, headers)).execute().asJsoup()
        val scriptData = playerDoc.selectFirst("script:containsData(JuicyCodes.Run)")
            ?.data()
            ?: return emptyList()

        val decodedData = scriptData.substringAfter("(").substringBefore(")")
            .split("+\"")
            .joinToString("") { it.replace("\"", "") }
            .let { Base64.decode(it, Base64.DEFAULT) }
            .let(::String)

        val unpackedJs = Unpacker.unpack(decodedData).ifEmpty { return emptyList() }

        return if ("googlevideo" in unpackedJs) {
            unpackedJs.substringAfter("sources:").substringBefore("]")
                .split("{")
                .drop(1)
                .map {
                    val videoUrl = it.substringAfter("file\":\"").substringBefore('"')
                    val quality = it.substringAfter("label\":\"").substringBefore('"')
                    Video(videoUrl, "Rinecloud - $quality", videoUrl, headers)
                }
        } else {
            val masterPlaylistUrl = unpackedJs.substringAfter("sources:")
                .substringAfter("file\":\"")
                .substringBefore('"')

            playlistUtils.extractFromHls(masterPlaylistUrl, videoNameGen = { "Rinecloud - $it" })
        }
    }
}

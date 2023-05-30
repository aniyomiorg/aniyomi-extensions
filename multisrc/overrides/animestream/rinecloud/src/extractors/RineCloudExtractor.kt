package eu.kanade.tachiyomi.animeextension.pt.rinecloud.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class RineCloudExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
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

        val masterPlaylistUrl = unpackedJs.substringAfter("sources:[")
            .substringAfter("file\":\"")
            .substringBefore('"')

        val playlistData = client.newCall(GET(masterPlaylistUrl, headers)).execute()
            .body.string()

        val separator = "#EXT-X-STREAM-INF:"
        return playlistData.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore("\n")
                .substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, "RineCloud - $quality", videoUrl, headers = headers)
        }
    }
}

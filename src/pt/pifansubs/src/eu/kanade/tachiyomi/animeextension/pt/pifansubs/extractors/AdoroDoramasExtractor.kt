package eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class AdoroDoramasExtractor(private val client: OkHttpClient) {

    private val PLAYER_NAME = "AdoroDoramas"

    fun videosFromUrl(url: String): List<Video> {
        val body = client.newCall(GET(url)).execute()
            .body?.string().orEmpty()
        val unpacked = JsUnpacker.unpackAndCombine(body)
            ?.replace("\\", "")
            ?: return emptyList<Video>()
        val listStr = unpacked.substringAfter("sources:[").substringBefore("]")
        return listStr.split("}").filter { it.isNotBlank() }.map {
            val quality = it.substringAfter("label':'").substringBefore("'")
            val videoUrl = it.substringAfter("file':'").substringBefore("'")
            Video(url, "$PLAYER_NAME:$quality", videoUrl)
        }
    }
}

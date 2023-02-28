package eu.kanade.tachiyomi.animeextension.id.kuronime.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class HxFileExtractor(private val client: OkHttpClient) {
    fun getVideoFromUrl(url: String, name: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val packed = document.selectFirst("script:containsData(eval\\(function\\()")!!.data()
        val unpacked = JsUnpacker.unpackAndCombine(packed) ?: return emptyList()
        val videoUrl = unpacked.substringAfter("\"type\":\"video").substringAfter("\"file\":\"").substringBefore("\"")
        return listOf(
            Video(videoUrl, "Original - $name", videoUrl, headers = Headers.headersOf("Referer", "https://hxfile.co/")),
        )
    }
}

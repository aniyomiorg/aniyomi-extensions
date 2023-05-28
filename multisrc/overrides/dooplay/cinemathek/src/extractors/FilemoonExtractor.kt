package eu.kanade.tachiyomi.animeextension.de.cinemathek.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class FilemoonExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        return runCatching {
            val doc = client.newCall(GET(url)).execute().asJsoup()
            val jsEval = doc.selectFirst("script:containsData(eval)")!!.data()
            val masterUrl = JsUnpacker.unpackAndCombine(jsEval)
                ?.substringAfter("{file:\"")
                ?.substringBefore("\"}")
                ?: return emptyList()

            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()
            val separator = "#EXT-X-STREAM-INF:"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val quality = "Filemoon:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl)
            }
        }.getOrElse { emptyList() }
    }
}

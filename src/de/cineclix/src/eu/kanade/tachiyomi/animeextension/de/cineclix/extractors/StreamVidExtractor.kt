package eu.kanade.tachiyomi.animeextension.de.cineclix.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamVidExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = "", suffix: String = ""): List<Video> {
        return runCatching {
            val doc = client.newCall(GET(url)).execute().asJsoup()

            val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
                ?.let(JsUnpacker::unpackAndCombine)
            val masterUrl = script?.substringAfter("sources:[{src:\"")?.substringBefore("\",")
            val masterPlaylist = client.newCall(GET(masterUrl!!)).execute().body.string()
            val separator = "#EXT-X-STREAM-INF:"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val resolution = it.substringAfter("RESOLUTION=")
                    .substringAfter("x")
                    .substringBefore(",") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                val quality = "${prefix}StreamVid - $resolution$suffix"
                Video(
                    videoUrl,
                    quality,
                    videoUrl,
                )
            }
        }.getOrElse { emptyList() }
    }
}

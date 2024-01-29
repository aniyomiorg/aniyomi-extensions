package eu.kanade.tachiyomi.lib.mp4uploadextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class Mp4uploadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, prefix: String = "", suffix: String = ""): List<Video> {
        val newHeaders = headers.newBuilder()
            .set("referer", REFERER)
            .build()

        val doc = client.newCall(GET(url, newHeaders)).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: doc.selectFirst("script:containsData(player.src)")?.data()
            ?: return emptyList()

        val videoUrl = script.substringAfter(".src(").substringBefore(")")
            .substringAfter("src:").substringAfter('"').substringBefore('"')

        val resolution = QUALITY_REGEX.find(script)?.groupValues?.let { "${it[1]}p" } ?: "Unknown resolution"
        val quality = "${prefix}Mp4Upload - $resolution$suffix"

        return listOf(Video(videoUrl, quality, videoUrl, newHeaders))
    }

    companion object {
        private val QUALITY_REGEX by lazy { """\WHEIGHT=(\d+)""".toRegex() }
        private const val REFERER = "https://mp4upload.com/"
    }
}

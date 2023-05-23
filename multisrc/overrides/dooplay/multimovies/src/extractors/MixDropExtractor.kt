package eu.kanade.tachiyomi.animeextension.en.multimovies.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class MixDropExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, lang: String = "", prefix: String = ""): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let { JsUnpacker.unpackAndCombine(it) }
            ?: return emptyList<Video>()
        val videoUrl = "https:" + unpacked.substringAfter("Core.wurl=\"")
            .substringBefore("\"")
        val quality = ("MixDrop").let {
            if (lang.isNotBlank()) {
                "$it($lang)"
            } else {
                it
            }
        }
        val referer = Headers.headersOf("Referer", "https://mixdrop.co/")
        return listOf(Video(url, prefix + quality, videoUrl, headers = referer))
    }
}

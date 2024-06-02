package eu.kanade.tachiyomi.animeextension.pt.doramogo.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class DoramogoExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()

        val script = document.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")
            ?.data()
            ?.replace(Regex("[\\u00E0-\\u00FC]"), "-") // Fix a bug in JsUnpacker with accents
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        return script.substringAfter("sources:")
            .substringBefore("]")
            .split("{")
            .drop(1)
            .map { line ->
                val url = line.substringAfter("file:\\'").substringBefore("\\'")
                val quality = line.substringAfter("label:\\'").substringBefore("\\'")

                Video(url, "Doramogo - $quality", url, headers)
            }
    }
}

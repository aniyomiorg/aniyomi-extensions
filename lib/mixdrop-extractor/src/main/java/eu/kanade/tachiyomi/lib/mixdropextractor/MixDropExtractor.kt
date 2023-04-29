package eu.kanade.tachiyomi.lib.mixdropextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLDecoder

class MixDropExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, lang: String = "", prefix: String = ""): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let(Unpacker::unpack)
            ?: return emptyList()

        val videoUrl = "https:" + unpacked.substringAfter("Core.wurl=\"")
            .substringBefore("\"")

        val subs = if ("Core.remotesub" in unpacked) {
            val subUrl = unpacked.substringAfter("Core.remotesub=\"").substringBefore("\"")
            listOf(Track(URLDecoder.decode(subUrl, "utf-8"), "sub"))
        } else {
            emptyList()
        }

        val quality = prefix + ("MixDrop").let {
            when {
                lang.isNotBlank() -> "$it($lang)"
                else -> it
            }
        }

        val headers = Headers.headersOf("Referer", "https://mixdrop.co/")
        return listOf(Video(videoUrl, quality, videoUrl, headers = headers, subtitleTracks = subs))
    }
}

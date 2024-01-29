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
    fun videoFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ): List<Video> {
        val headers = Headers.headersOf("Referer", referer)
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let(Unpacker::unpack)
            ?: return emptyList()

        val videoUrl = "https:" + unpacked.substringAfter("Core.wurl=\"")
            .substringBefore("\"")

        val subs = unpacked.substringAfter("Core.remotesub=\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.let { listOf(Track(URLDecoder.decode(it, "utf-8"), "sub")) }
            ?: emptyList()

        val quality = buildString {
            append("${prefix}MixDrop")
            if (lang.isNotBlank()) append("($lang)")
        }

        return listOf(Video(videoUrl, quality, videoUrl, headers = headers, subtitleTracks = subs + externalSubs))
    }

    fun videosFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ) = videoFromUrl(url, lang, prefix, externalSubs, referer)
}

private const val DEFAULT_REFERER = "https://mixdrop.co/"

package eu.kanade.tachiyomi.animeextension.hi.yomovies.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MovembedExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).execute().asJsoup()

        return document.select("ul.list-server-items > li.linkserver").parallelMap { server ->
            runCatching {
                extractVideosFromIframe(server.attr("abs:data-video"))
            }.getOrElse { emptyList() }
        }.flatten()
    }

    private fun extractVideosFromIframe(iframeUrl: String): List<Video> {
        return when {
            STREAM_SB_DOMAINS.any { iframeUrl.contains(it) } -> {
                val url = iframeUrl.toHttpUrl()
                val subtitleList = url.queryParameter("caption_1")?.let { t ->
                    listOf(Track(t, url.queryParameter("sub_1") ?: "English"))
                } ?: emptyList()

                StreamSBExtractor(client).videosFromUrl(iframeUrl, headers, prefix = "(movembed) StreamSB - ", externalSubs = subtitleList)
            }
            MIXDROP_DOMAINS.any { iframeUrl.contains(it) } -> {
                val url = iframeUrl.toHttpUrl()
                val subtitleList = url.queryParameter("sub1")?.let { t ->
                    listOf(Track(t, url.queryParameter("sub1_label") ?: "English"))
                } ?: emptyList()

                MixDropExtractor(client).videoFromUrl(iframeUrl, prefix = "(movembed) - ", externalSubs = subtitleList)
            }
            iframeUrl.startsWith("https://doo") -> {
                val url = iframeUrl.toHttpUrl()
                val subtitleList = url.queryParameter("c1_file")?.let { t ->
                    listOf(Track(t, url.queryParameter("c1_label") ?: "English"))
                } ?: emptyList()

                DoodExtractor(client).videoFromUrl(iframeUrl, "(movembed) Dood", externalSubs = subtitleList)?.let(::listOf) ?: emptyList()
            }
            else -> emptyList()
        }
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private val STREAM_SB_DOMAINS = listOf(
            "sbhight", "sbrity", "sbembed.com", "sbembed1.com", "sbplay.org",
            "sbvideo.net", "streamsb.net", "sbplay.one", "cloudemb.com",
            "playersb.com", "tubesb.com", "sbplay1.com", "embedsb.com",
            "watchsb.com", "sbplay2.com", "japopav.tv", "viewsb.com",
            "sbfast", "sbfull.com", "javplaya.com", "ssbstream.net",
            "p1ayerjavseen.com", "sbthe.com", "vidmovie.xyz", "sbspeed.com",
            "streamsss.net", "sblanh.com", "tvmshow.com", "sbanh.com",
            "streamovies.xyz", "sblona.com", "sbnet.one",
        )

        private val MIXDROP_DOMAINS = listOf(
            "mixdrop",
            "mixdroop",
        )
    }
}

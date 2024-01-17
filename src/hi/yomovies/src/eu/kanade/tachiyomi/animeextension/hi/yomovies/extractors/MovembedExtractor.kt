package eu.kanade.tachiyomi.animeextension.hi.yomovies.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MovembedExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).execute().asJsoup()

        return document.select("ul.list-server-items > li.linkserver")
            .parallelCatchingFlatMapBlocking { server ->
                extractVideosFromIframe(server.attr("abs:data-video"))
            }
    }

    private fun extractVideosFromIframe(iframeUrl: String): List<Video> {
        return when {
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

    companion object {

        private val MIXDROP_DOMAINS = listOf(
            "mixdrop",
            "mixdroop",
        )
    }
}

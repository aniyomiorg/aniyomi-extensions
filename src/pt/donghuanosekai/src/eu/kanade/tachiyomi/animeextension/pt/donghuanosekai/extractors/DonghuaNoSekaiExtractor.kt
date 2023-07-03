package eu.kanade.tachiyomi.animeextension.pt.donghuanosekai.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class DonghuaNoSekaiExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    fun videosFromDocument(document: Document): List<Video> {
        val iframe = document.selectFirst("iframe")
        val playerId = document.location().toHttpUrl()
            .queryParameter("type")
            ?.toIntOrNull()?.plus(1) ?: 1
        val playerName = "Player $playerId"

        return when (iframe) {
            null -> {
                val source = document.selectFirst("video > source") ?: return emptyList()
                val quality = source.attr("size") + "p"
                val url = source.attr("src")
                listOf(Video(url, "$playerName - $quality", url, headers))
            }
            else -> {
                val iframeUrl = iframe.attr("src")
                when {
                    iframeUrl.contains("nativov2.php") || iframeUrl.contains("/embed2/") -> {
                        val url = iframeUrl.toHttpUrl().let {
                            it.queryParameter("id") ?: it.queryParameter("v")
                        } ?: return emptyList()

                        val quality = url.substringAfter("_").substringBefore("_")
                        listOf(Video(url, "$playerName - $quality", url, headers))
                    }
                    else -> getVideosFromIframeUrl(iframeUrl, playerName)
                }
            }
        }
    }

    private fun getVideosFromIframeUrl(iframeUrl: String, playerName: String): List<Video> {
        return when {
            iframeUrl.contains("sbdnsk") || iframeUrl.contains("/e/") -> {
                StreamSBExtractor(client).videosFromUrl(iframeUrl, headers, playerName)
            }
            iframeUrl.contains("playerB.php") -> {
                client.newCall(GET(iframeUrl, headers)).execute().use {
                    it.body.string()
                        .substringAfter("sources:")
                        .substringBefore("]")
                        .split("{")
                        .drop(1)
                        .map {
                            val url = it.substringAfter("file: \"").substringBefore('"')
                            val quality = it.substringAfter("label: \"").substringBefore('"')
                            Video(url, "$playerName - $quality", url, headers)
                        }
                }
            }
            else -> emptyList()
        }
    }
}

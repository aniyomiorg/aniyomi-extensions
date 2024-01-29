package eu.kanade.tachiyomi.animeextension.pt.donghuanosekai.extractors

import eu.kanade.tachiyomi.animesource.model.Video
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
                        val url = iframeUrl.toHttpUrl().run {
                            queryParameter("id") ?: queryParameter("v")
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
            iframeUrl.contains("playerB.php") -> {
                client.newCall(GET(iframeUrl, headers)).execute().body.string()
                    .substringAfter("sources:")
                    .substringBefore("]")
                    .split("{")
                    .drop(1)
                    .map { line ->
                        val url = line.substringAfter("file: \"").substringBefore('"')
                        val quality = line.substringAfter("label: \"")
                            .substringBefore('"')
                            .run {
                                when (this) {
                                    "SD" -> "480p"
                                    "HD" -> "720p"
                                    "FHD", "FULLHD" -> "1080p"
                                    else -> this
                                }
                            }
                        Video(url, "$playerName - $quality", url, headers)
                    }
            }
            else -> emptyList()
        }
    }
}

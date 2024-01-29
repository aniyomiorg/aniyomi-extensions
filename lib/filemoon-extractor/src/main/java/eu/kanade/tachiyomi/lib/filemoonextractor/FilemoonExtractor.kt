package eu.kanade.tachiyomi.lib.filemoonextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class FilemoonExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = "Filemoon - ", headers: Headers? = null): List<Video> {
        return runCatching {
            val httpUrl = url.toHttpUrl()
            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder())
                .set("Referer", url)
                .set("Origin", "https://${httpUrl.host}")
                .build()

            val doc = client.newCall(GET(url, videoHeaders)).execute().asJsoup()
            val jsEval = doc.selectFirst("script:containsData(eval):containsData(m3u8)")!!.data()
            val unpacked = JsUnpacker.unpackAndCombine(jsEval).orEmpty()
            val masterUrl = unpacked.takeIf(String::isNotBlank)
                ?.substringAfter("{file:\"", "")
                ?.substringBefore("\"}", "")
                ?.takeIf(String::isNotBlank)
                ?: return emptyList()

            val subtitleTracks = buildList {
                // Subtitles from a external URL
                val subUrl = httpUrl.queryParameter("sub.info")
                    ?: unpacked.substringAfter("fetch('", "")
                        .substringBefore("').")
                        .takeIf(String::isNotBlank)
                if (subUrl != null) {
                    runCatching { // to prevent failures on serialization errors
                        client.newCall(GET(subUrl, videoHeaders)).execute()
                            .body.string()
                            .let { json.decodeFromString<List<SubtitleDto>>(it) }
                            .forEach { add(Track(it.file, it.label)) }
                    }
                }
            }

            PlaylistUtils(client, videoHeaders).extractFromHls(
                masterUrl,
                subtitleList = subtitleTracks,
                videoNameGen = { "$prefix$it" },
            )
        }.getOrElse { emptyList() }
    }

    @Serializable
    data class SubtitleDto(val file: String, val label: String)
}

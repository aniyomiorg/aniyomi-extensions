package eu.kanade.tachiyomi.lib.filemoonextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
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
            val doc = client.newCall(GET(url)).execute().asJsoup()
            val jsEval = doc.selectFirst("script:containsData(eval):containsData(m3u8)")!!.data()
            val unpacked = JsUnpacker.unpackAndCombine(jsEval).orEmpty()
            val masterUrl = unpacked.takeIf(String::isNotBlank)
                ?.substringAfter("{file:\"", "")
                ?.substringBefore("\"}", "")
                ?.takeIf(String::isNotBlank)
                ?: return emptyList()

            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()

            val httpUrl = url.toHttpUrl()
            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder())
                .set("Referer", url)
                .set("Origin", "https://${httpUrl.host}")
                .build()

            val subtitleTracks = buildList {
                // Subtitles from a external URL
                val subUrl = httpUrl.queryParameter("sub.info")
                    ?: unpacked.substringAfter("fetch('", "")
                        .substringBefore("').")
                        .takeIf(String::isNotBlank)
                if (subUrl != null) {
                    runCatching { // to prevent failures on serialization errors
                        client.newCall(GET(subUrl, videoHeaders)).execute()
                            .use { it.body.string() }
                            .let { json.decodeFromString<List<SubtitleDto>>(it) }
                            .forEach { add(Track(it.file, it.label)) }
                    }
                }

                SUBTITLES_REGEX // Subtitles from the playlist
                    .findAll(masterPlaylist)
                    .forEach { add(Track(it.groupValues[2], it.groupValues[1])) }
            }
            val audioTracks = AUDIO_REGEX
                .findAll(masterPlaylist)
                .map { Track(it.groupValues[2], it.groupValues[1]) }
                .toList()

            val separator = "#EXT-X-STREAM-INF:"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val resolution = it.substringAfter("RESOLUTION=")
                    .substringAfter("x")
                    .substringBefore(",") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")

                Video(
                    videoUrl,
                    prefix + resolution,
                    videoUrl,
                    headers = videoHeaders,
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks,
                )
            }
        }.getOrElse { emptyList() }
    }

    @Serializable
    data class SubtitleDto(
        val file: String,
        val label: String,
    )

    companion object {
        private val SUBTITLES_REGEX by lazy {
            Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")
        }

        private val AUDIO_REGEX by lazy {
            Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""")
        }
    }
}

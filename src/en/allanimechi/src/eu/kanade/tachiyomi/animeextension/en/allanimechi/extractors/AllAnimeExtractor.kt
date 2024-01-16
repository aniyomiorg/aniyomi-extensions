package eu.kanade.tachiyomi.animeextension.en.allanimechi.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class AllAnimeExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val jsonHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Host", url.toHttpUrl().host)
            add("Referer", url)
            add("Sec-Fetch-Dest", "empty")
            add("Sec-Fetch-Mode", "cors")
            add("Sec-Fetch-Site", "same-origin")
            add("X-Requested-With", "Y29tLmFsbGFuaW1lLmFuaW1lY2hpY2tlbg==".decodeBase64())
        }.build()

        val decoded = url.toHttpUrl().queryParameter("source")?.decodeBase64() ?: return emptyList()
        val slug = json.decodeFromString<SourceUrl>(decoded).idUrl

        val data = client.newCall(
            GET("https://${url.toHttpUrl().host}$slug", headers = jsonHeaders),
        ).execute().parseAs<VideoData>()

        return data.links.flatMap { link ->
            val subtitleList = link.subtitles.map {
                Track(it.src, it.label)
            }

            val audioList = link.rawUrls.audios.map {
                Track(it.url, formatBytes(it.bandwidth) + "/s")
            }

            link.rawUrls.vids.map { vid ->
                val bandwidth = formatBytes(vid.bandwidth) + "/s"
                val name = "$prefix${vid.height}p ($bandwidth)"

                Video(vid.url, name, vid.url, subtitleTracks = subtitleList, audioTracks = audioList)
            }
        }
    }

    // ============================= Utilities ==============================

    private fun String.decodeBase64(): String {
        return String(Base64.decode(this, Base64.DEFAULT))
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes"
            bytes == 1L -> "$bytes byte"
            else -> ""
        }
    }

    @Serializable
    data class SourceUrl(
        val idUrl: String,
    )

    @Serializable
    data class VideoData(
        val links: List<LinkObject>,
    ) {
        @Serializable
        data class LinkObject(
            val resolutionStr: String,
            val rawUrls: UrlObject,
            val subtitles: List<SubtitleObject>,
        ) {
            @Serializable
            data class UrlObject(
                val vids: List<VideoObject>,
                val audios: List<AudioObject>,
            ) {
                @Serializable
                data class VideoObject(
                    val bandwidth: Long,
                    val height: Int,
                    val url: String,
                )

                @Serializable
                data class AudioObject(
                    val bandwidth: Long,
                    val url: String,
                )
            }

            @Serializable
            data class SubtitleObject(
                val label: String,
                val src: String,
            )
        }
    }
}

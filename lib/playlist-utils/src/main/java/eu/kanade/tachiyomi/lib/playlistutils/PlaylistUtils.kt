package eu.kanade.tachiyomi.lib.playlistutils

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.commonEmptyHeaders
import org.jsoup.Jsoup

class PlaylistUtils(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
// ================================ M3U8 ================================

    // In case you want to specify specific headers for master playlist & the videos
    fun extractFromHls(
        playlistUrl: String,
        referer: String = "",
        masterHeaders: Headers,
        videoHeaders: Headers,
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
    ): List<Video> {
        return extractFromHls(
            playlistUrl,
            referer,
            { _, _ -> masterHeaders },
            { _, _, _ -> videoHeaders },
            videoNameGen,
            subtitleList,
            audioList
        )
    }

    fun extractFromHls(
        playlistUrl: String,
        referer: String = "",
        masterHeadersGen: (Headers, String) -> Headers = { baseHeaders, referer ->
            generateMasterHeaders(baseHeaders, referer)
        },
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, videoUrl ->
            generateMasterHeaders(baseHeaders, referer)
        },
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
    ): List<Video> {
        val masterHeaders = masterHeadersGen(headers, referer)

        val masterPlaylist = client.newCall(
            GET(playlistUrl, headers = masterHeaders)
        ).execute().body.string()

        // Check if there isn't multiple streams available
        if (PLAYLIST_SEPARATOR !in masterPlaylist) {
            return listOf(
                Video(
                    playlistUrl, videoNameGen("Video"), playlistUrl, headers = masterHeaders, subtitleTracks = subtitleList, audioTracks = audioList
                )
            )
        }

        val masterBase = "https://${playlistUrl.toHttpUrl().host}${playlistUrl.toHttpUrl().encodedPath}"
            .substringBeforeLast("/") + "/"

        // Get subtitles
        val subtitleTracks = subtitleList.ifEmpty {
            SUBTITLE_REGEX.findAll(masterPlaylist).mapNotNull {
                Track(
                    getAbsoluteUrl(it.groupValues[2], playlistUrl, masterBase) ?: return@mapNotNull null,
                    it.groupValues[1]
                )
            }.toList()
        }

        // Get audio tracks
        val audioTracks = audioList.ifEmpty {
            AUDIO_REGEX.findAll(masterPlaylist).mapNotNull {
                Track(
                    getAbsoluteUrl(it.groupValues[2], playlistUrl, masterBase) ?: return@mapNotNull null,
                    it.groupValues[1]
                )
            }.toList()
        }

        return masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).mapNotNull {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringBefore("\n")
                .substringAfter("x")
                .substringBefore(",") + "p"

            val videoUrl = it.substringAfter("\n").substringBefore("\n").let { url ->
                getAbsoluteUrl(url, playlistUrl, masterBase)
            } ?: return@mapNotNull null

            Video(
                videoUrl, videoNameGen(resolution), videoUrl,
                headers = videoHeadersGen(headers, referer, videoUrl),
                subtitleTracks = subtitleTracks, audioTracks = audioTracks
            )
        }
    }

    private fun getAbsoluteUrl(url: String, playlistUrl: String, masterBase: String): String? {
        return when {
            url.isEmpty() -> null
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://" + playlistUrl.toHttpUrl().host + url
            else -> masterBase + url
        }
    }

    private fun generateMasterHeaders(baseHeaders: Headers, referer: String): Headers {
        return baseHeaders.newBuilder().apply {
            add("Accept", "*/*")
            if (referer.isNotEmpty()) {
                add("Origin", "https://${referer.toHttpUrl().host}")
                add("Referer", referer)
            }
        }.build()
    }

    // ================================ DASH ================================

    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String) -> String,
        mpdHeaders: Headers,
        videoHeaders: Headers,
        referer: String = "",
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
    ): List<Video> {
        return extractFromDash(
            mpdUrl,
            { videoRes, bitRate ->
                videoNameGen(videoRes) + " - ${formatBytes(bitRate.toLongOrNull())}"
            },
            referer,
            { _, _ -> mpdHeaders},
            { _, _, _ -> videoHeaders},
            subtitleList,
            audioList
        )
    }

    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String) -> String,
        referer: String = "",
        mpdHeadersGen: (Headers, String) -> Headers = { baseHeaders, referer ->
            generateMasterHeaders(baseHeaders, referer)
        },
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, videoUrl ->
            generateMasterHeaders(baseHeaders, referer)
        },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
    ): List<Video> {
        return extractFromDash(
            mpdUrl,
            { videoRes, bitRate ->
                videoNameGen(videoRes) + " - ${formatBytes(bitRate.toLongOrNull())}"
            },
            referer,
            mpdHeadersGen,
            videoHeadersGen,
            subtitleList,
            audioList
        )
    }

    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String, String) -> String,
        referer: String = "",
        mpdHeadersGen: (Headers, String) -> Headers = { baseHeaders, referer ->
            generateMasterHeaders(baseHeaders, referer)
        },
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, videoUrl ->
            generateMasterHeaders(baseHeaders, referer)
        },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
    ): List<Video> {
        val mpdHeaders = mpdHeadersGen(headers, referer)

        val doc = client.newCall(
            GET(mpdUrl, headers = mpdHeaders)
        ).execute().asJsoup()

        val audioTracks = audioList.ifEmpty {
            doc.select("Representation[mimetype~=audio]").map { audioSrc ->
                val bandwidth = audioSrc.attr("bandwidth").toLongOrNull()
                Track(audioSrc.text(), formatBytes(bandwidth))
            }
        }

        return doc.select("Representation[mimetype~=video]").map { videoSrc ->
            val bandwidth = videoSrc.attr("bandwidth")
            val res = videoSrc.attr("height")

            Video(
                videoSrc.text(),
                videoNameGen(res, bandwidth),
                videoSrc.text(),
                audioTracks = audioTracks,
                subtitleTracks = subtitleList,
                headers = videoHeadersGen(headers, referer, videoSrc.text())
            )
        }
    }

    private fun formatBytes(bytes: Long?): String {
        return when {
            bytes == null -> ""
            bytes >= 1_000_000_000 -> "%.2f GB/s".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB/s".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB/s".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes/s"
            bytes == 1L -> "$bytes byte/s"
            else -> ""
        }
    }

    // ============================= Utilities ==============================

    companion object {
        private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"

        private val SUBTITLE_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val AUDIO_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""") }
    }
}

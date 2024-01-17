package eu.kanade.tachiyomi.lib.playlistutils

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.commonEmptyHeaders

class PlaylistUtils(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {

    // ================================ M3U8 ================================

    /**
     * Extracts videos from a .m3u8 file.
     *
     * @param playlistUrl the URL of the HLS playlist
     * @param referer the referer header value to be sent in the HTTP request (default: "")
     * @param masterHeaders header for the master playlist
     * @param videoHeaders headers for each video
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video (default: identity function)
     * @param subtitleList a list of subtitle tracks associated with the HLS playlist, will append to subtitles present in the m3u8 playlist (default: empty list)
     * @param audioList a list of audio tracks associated with the HLS playlist, will append to audio tracks present in the m3u8 playlist (default: empty list)
     * @return a list of Video objects
     */
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
            audioList,
        )
    }

    /**
     * Extracts videos from a .m3u8 file.
     *
     * @param playlistUrl the URL of the HLS playlist
     * @param referer the referer header value to be sent in the HTTP request (default: "")
     * @param masterHeadersGen a function that generates headers for the master playlist request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - Returns the updated headers for the master playlist request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoHeadersGen a function that generates headers for each video request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - The third parameter `videoUrl` represents the URL of the video
     *     - Returns the updated headers for the video request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video (default: identity function)
     * @param subtitleList a list of subtitle tracks associated with the HLS playlist, will append to subtitles present in the m3u8 playlist (default: empty list)
     * @param audioList a list of audio tracks associated with the HLS playlist, will append to audio tracks present in the m3u8 playlist (default: empty list)
     * @return a list of Video objects
     */
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

        val masterPlaylist = client.newCall(GET(playlistUrl, masterHeaders)).execute()
            .use { it.body.string() }

        // Check if there isn't multiple streams available
        if (PLAYLIST_SEPARATOR !in masterPlaylist) {
            return listOf(
                Video(
                    playlistUrl,
                    videoNameGen("Video"),
                    playlistUrl,
                    headers = masterHeaders,
                    subtitleTracks = subtitleList,
                    audioTracks = audioList,
                ),
            )
        }

        val playlistHttpUrl = playlistUrl.toHttpUrl()

        val masterUrlBasePath = playlistHttpUrl.newBuilder().apply {
            removePathSegment(playlistHttpUrl.pathSize - 1)
            addPathSegment("")
            query(null)
            fragment(null)
        }.build().toString()

        // Get subtitles
        val subtitleTracks = subtitleList + SUBTITLE_REGEX.findAll(masterPlaylist).mapNotNull {
            Track(
                getAbsoluteUrl(it.groupValues[2], playlistUrl, masterUrlBasePath) ?: return@mapNotNull null,
                it.groupValues[1],
            )
        }.toList()

        // Get audio tracks
        val audioTracks = audioList + AUDIO_REGEX.findAll(masterPlaylist).mapNotNull {
            Track(
                getAbsoluteUrl(it.groupValues[2], playlistUrl, masterUrlBasePath) ?: return@mapNotNull null,
                it.groupValues[1],
            )
        }.toList()

        return masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).mapNotNull {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringBefore("\n")
                .substringAfter("x")
                .substringBefore(",") + "p"

            val videoUrl = it.substringAfter("\n").substringBefore("\n").let { url ->
                getAbsoluteUrl(url, playlistUrl, masterUrlBasePath)
            } ?: return@mapNotNull null

            Video(
                videoUrl,
                videoNameGen(resolution),
                videoUrl,
                headers = videoHeadersGen(headers, referer, videoUrl),
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
            )
        }
    }

    private fun getAbsoluteUrl(url: String, playlistUrl: String, masterBase: String): String? {
        return when {
            url.isEmpty() -> null
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> playlistUrl.toHttpUrl().newBuilder().encodedPath("/").build().toString()
                .substringBeforeLast("/") + url
            else -> masterBase + url
        }
    }

    fun generateMasterHeaders(baseHeaders: Headers, referer: String): Headers {
        return baseHeaders.newBuilder().apply {
            set("Accept", "*/*")
            if (referer.isNotEmpty()) {
                set("Origin", "https://${referer.toHttpUrl().host}")
                set("Referer", referer)
            }
        }.build()
    }

    // ================================ DASH ================================

    /**
     * Extracts video information from a DASH .mpd file.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video
     * @param mpdHeaders the headers to be sent in the HTTP request for the MPD file
     * @param videoHeaders the headers to be sent in the HTTP requests for video segments
     * @param referer the referer header value to be sent in the HTTP requests (default: "")
     * @param subtitleList a list of subtitle tracks associated with the DASH file, will append to subtitles present in the dash file (default: empty list)
     * @param audioList a list of audio tracks associated with the DASH file, will append to audio tracks present in the dash file (default: empty list)
     * @return a list of Video objects
     */
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
            { videoRes, bandwidth ->
                videoNameGen(videoRes) + " - ${formatBytes(bandwidth.toLongOrNull())}"
            },
            referer,
            { _, _ -> mpdHeaders },
            { _, _, _ -> videoHeaders },
            subtitleList,
            audioList,
        )
    }

    /**
     * Extracts video information from a DASH .mpd file.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video, with ` - <BANDWIDTH>` added to the end
     * @param referer the referer header value to be sent in the HTTP requests (default: "")
     * @param mpdHeadersGen a function that generates headers for the .mpd request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - Returns the updated headers for the .mpd request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoHeadersGen a function that generates headers for each video request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - The third parameter `videoUrl` represents the URL of the video
     *     - Returns the updated headers for the video segment request (default: generateMasterHeaders(baseHeaders, referer))
     * @param subtitleList a list of subtitle tracks associated with the DASH file, will append to subtitles present in the dash file (default: empty list)
     * @param audioList a list of audio tracks associated with the DASH file, will append to audio tracks present in the dash file (default: empty list)
     * @return a list of Video objects
     */
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
            { videoRes, bandwidth ->
                videoNameGen(videoRes) + " - ${formatBytes(bandwidth.toLongOrNull())}"
            },
            referer,
            mpdHeadersGen,
            videoHeadersGen,
            subtitleList,
            audioList,
        )
    }

    /**
     * Extracts video information from a DASH .mpd file.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param videoNameGen a function that generates a custom name for each video based on its quality and bandwidth
     *     - The parameter `quality` represents the quality of the video segment
     *     - The parameter `bandwidth` represents the bandwidth of the video segment, in bytes
     *     - Returns the custom name for the video
     * @param referer the referer header value to be sent in the HTTP requests (default: "")
     * @param mpdHeadersGen a function that generates headers for the .mpd request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - Returns the updated headers for the .mpd request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoHeadersGen a function that generates headers for each video request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - The third parameter `videoUrl` represents the URL of the video
     *     - Returns the updated headers for the video segment request (default: generateMasterHeaders(baseHeaders, referer))
     * @param subtitleList a list of subtitle tracks associated with the DASH file, will append to subtitles present in the dash file (default: empty list)
     * @param audioList a list of audio tracks associated with the DASH file, will append to audio tracks present in the dash file (default: empty list)
     * @return a list of Video objects
     */
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

        val doc = client.newCall(GET(mpdUrl, mpdHeaders)).execute()
            .use { it.asJsoup() }

        // Get audio tracks
        val audioTracks = audioList + doc.select("Representation[mimetype~=audio]").map { audioSrc ->
            val bandwidth = audioSrc.attr("bandwidth").toLongOrNull()
            Track(audioSrc.text(), formatBytes(bandwidth))
        }

        return doc.select("Representation[mimetype~=video]").map { videoSrc ->
            val bandwidth = videoSrc.attr("bandwidth")
            val res = videoSrc.attr("height") + "p"
            val videoUrl = videoSrc.text()

            Video(
                videoUrl,
                videoNameGen(res, bandwidth),
                videoUrl,
                audioTracks = audioTracks,
                subtitleTracks = subtitleList,
                headers = videoHeadersGen(headers, referer, videoUrl),
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

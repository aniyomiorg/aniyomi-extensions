package eu.kanade.tachiyomi.animeextension.all.animexin.extractors

import android.annotation.SuppressLint
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import kotlin.math.abs

class YouTubeExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        // Ported from https://github.com/dermasmid/scrapetube/blob/master/scrapetube/scrapetube.py
        // TODO: Make code prettier
        // GET KEY

        val videoId = url.substringAfter("/embed/")

        val document = client.newCall(GET(url.replace("/embed/", "/watch?v=")))
            .execute()
            .asJsoup()

        val ytcfg = document.selectFirst("script:containsData(window.ytcfg=window.ytcfg)")
            ?.data() ?: run {
            Log.e("YouTubeExtractor", "Failed while trying to fetch the api key >:(")
            return emptyList()
        }

        val clientName = ytcfg.substringAfter("INNERTUBE_CONTEXT_CLIENT_NAME\":", "")
            .substringBefore(",", "").ifEmpty { "5" }

        val apiKey = ytcfg
            .substringAfter("innertubeApiKey\":\"", "")
            .substringBefore('"')

        val playerUrl = "$YOUTUBE_URL/youtubei/v1/player?key=$apiKey&prettyPrint=false"

        val body = """
            {
               "context":{
                  "client":{
                     "clientName":"IOS",
                     "clientVersion":"17.33.2",
                     "deviceModel": "iPhone14,3",
                     "userAgent": "com.google.ios.youtube/17.33.2 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X)",
                     "hl": "en",
                     "timeZone": "UTC",
                     "utcOffsetMinutes": 0
                  }
               },
               "videoId":"$videoId",
               "playbackContext":{
                  "contentPlaybackContext":{
                     "html5Preference":"HTML5_PREF_WANTS"
                  }
               },
               "contentCheckOk":true,
               "racyCheckOk":true
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val headers = Headers.Builder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            add("Origin", YOUTUBE_URL)
            add("User-Agent", "com.google.ios.youtube/17.33.2 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X)")
            add("X-Youtube-Client-Name", clientName)
            add("X-Youtube-Client-Version", "17.33.2")
        }.build()

        val ytResponse = client.newCall(POST(playerUrl, headers, body)).execute()
            .let { json.decodeFromString<YoutubeResponse>(it.body.string()) }

        val formats = ytResponse.streamingData.adaptiveFormats

        // Get Audio
        val audioTracks = formats.filter { it.mimeType.startsWith("audio/webm") }
            .map { Track(it.url, it.audioQuality!! + " (${formatBits(it.averageBitrate!!)}ps)") }

        // Get Subtitles
        val subs = ytResponse.captions?.renderer?.captionTracks?.map {
            Track(it.baseUrl, it.label)
        } ?: emptyList()

        // Get videos, finally
        return formats.filter { it.mimeType.startsWith("video/mp4") }.map {
            val codecs = it.mimeType.substringAfter("codecs=\"").substringBefore("\"")
            Video(
                it.url,
                prefix + it.qualityLabel.orEmpty() + " ($codecs)",
                it.url,
                subtitleTracks = subs,
                audioTracks = audioTracks,
            )
        }
    }

    @SuppressLint("DefaultLocale")
    fun formatBits(size: Long): String {
        var bits = abs(size)
        if (bits < 1000) {
            return "${bits}b"
        }
        val iterator = "kMGTPE".iterator()
        var currentChar = iterator.next()
        while (bits >= 999950 && iterator.hasNext()) {
            bits /= 1000
            currentChar = iterator.next()
        }
        return "%.0f%cb".format(bits / 1000.0, currentChar)
    }

    @Serializable
    data class YoutubeResponse(
        val streamingData: AdaptiveDto,
        val captions: CaptionsDto? = null,
    )

    @Serializable
    data class AdaptiveDto(val adaptiveFormats: List<TrackDto>)

    @Serializable
    data class TrackDto(
        val mimeType: String,
        val url: String,
        val averageBitrate: Long? = null,
        val qualityLabel: String? = null,
        val audioQuality: String? = null,
    )

    @Serializable
    data class CaptionsDto(
        @SerialName("playerCaptionsTracklistRenderer")
        val renderer: CaptionsRendererDto,
    ) {
        @Serializable
        data class CaptionsRendererDto(val captionTracks: List<CaptionItem>)

        @Serializable
        data class CaptionItem(val baseUrl: String, val name: NameDto) {
            @Serializable
            data class NameDto(val runs: List<GodDamnitYoutube>)

            @Serializable
            data class GodDamnitYoutube(val text: String)

            val label by lazy { name.runs.first().text }
        }
    }
}

private const val YOUTUBE_URL = "https://www.youtube.com"

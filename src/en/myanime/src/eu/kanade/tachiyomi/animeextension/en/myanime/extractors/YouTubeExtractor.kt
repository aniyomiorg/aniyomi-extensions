package eu.kanade.tachiyomi.animeextension.en.myanime.extractors

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.text.CharacterIterator
import java.text.StringCharacterIterator

class YouTubeExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        // Ported from https://github.com/dermasmid/scrapetube/blob/master/scrapetube/scrapetube.py
        // GET KEY
        var ytcfgString = ""
        val videoId = url.substringAfter("/embed/").substringBefore("?")

        val document = client.newCall(
            GET(url.replace("/embed/", "/watch?v=")),
        ).execute().asJsoup()

        for (element in document.select("script")) {
            val scriptData = element.data()
            if (scriptData.startsWith("(function() {window.ytplayer={};")) {
                ytcfgString = scriptData
            }
        }

        val apiKey = getKey(ytcfgString, "innertubeApiKey")

        val playerUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false"

        val body = """
            {
               "context":{
                  "client":{
                     "clientName":"ANDROID",
                     "clientVersion":"17.31.35",
                     "androidSdkVersion":30,
                     "userAgent":"com.google.android.youtube/17.31.35 (Linux; U; Android 11) gzip",
                     "hl":"en",
                     "timeZone":"UTC",
                     "utcOffsetMinutes":0
                  }
               },
               "videoId":"$videoId",
               "params":"8AEB",
               "playbackContext":{
                  "contentPlaybackContext":{
                     "html5Preference":"HTML5_PREF_WANTS"
                  }
               },
               "contentCheckOk":true,
               "racyCheckOk":true
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val headers = Headers.headersOf(
            "X-YouTube-Client-Name", "3",
            "X-YouTube-Client-Version", "17.31.35",
            "Origin", "https://www.youtube.com",
            "User-Agent", "com.google.android.youtube/17.31.35 (Linux; U; Android 11) gzip",
            "content-type", "application/json",
        )

        val postResponse = client.newCall(
            POST(playerUrl, headers = headers, body = body),
        ).execute()

        val responseObject = json.decodeFromString<JsonObject>(postResponse.body.string())
        val videoList = mutableListOf<Video>()

        val formats = responseObject["streamingData"]!!
            .jsonObject["adaptiveFormats"]!!
            .jsonArray

        val audioTracks = mutableListOf<Track>()
        val subtitleTracks = mutableListOf<Track>()

        // Get Audio
        for (format in formats) {
            if (format.jsonObject["mimeType"]!!.jsonPrimitive.content.startsWith("audio/webm")) {
                runCatching {
                    audioTracks.add(
                        Track(
                            format.jsonObject["url"]!!.jsonPrimitive.content,
                            format.jsonObject["audioQuality"]!!.jsonPrimitive.content +
                                " (${formatBits(format.jsonObject["averageBitrate"]!!.jsonPrimitive.long)}ps)",
                        ),
                    )
                }
            }
        }

        // Get Subtitles
        if (responseObject.containsKey("captions")) {
            val captionTracks = responseObject["captions"]!!
                .jsonObject["playerCaptionsTracklistRenderer"]!!
                .jsonObject["captionTracks"]!!
                .jsonArray

            for (caption in captionTracks) {
                val captionJson = caption.jsonObject
                runCatching {
                    subtitleTracks.add(
                        Track(
                            captionJson["baseUrl"]!!.jsonPrimitive.content.replace("srv3", "vtt"),
                            captionJson["name"]!!.jsonObject["runs"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
                        ),
                    )
                }
            }
        }

        // List formats
        for (format in formats) {
            val mimeType = format.jsonObject["mimeType"]!!.jsonPrimitive.content
            if (mimeType.startsWith("video/mp4")) {
                videoList.add(
                    try {
                        Video(
                            format.jsonObject["url"]!!.jsonPrimitive.content,
                            prefix + format.jsonObject["qualityLabel"]!!.jsonPrimitive.content +
                                " (${mimeType.substringAfter("codecs=\"").substringBefore("\"")})",
                            format.jsonObject["url"]!!.jsonPrimitive.content,
                            audioTracks = audioTracks,
                            subtitleTracks = subtitleTracks,
                        )
                    } catch (a: Exception) {
                        Video(
                            format.jsonObject["url"]!!.jsonPrimitive.content,
                            prefix + format.jsonObject["qualityLabel"]!!.jsonPrimitive.content +
                                " (${mimeType.substringAfter("codecs=\"").substringBefore("\"")})",
                            format.jsonObject["url"]!!.jsonPrimitive.content,
                        )
                    },

                )
            }
        }

        return videoList
    }

    fun getKey(string: String, key: String): String {
        var pattern = Regex("\"$key\":\"(.*?)\"")
        return pattern.find(string)?.groupValues?.get(1) ?: ""
    }

    @SuppressLint("DefaultLocale")
    fun formatBits(bits: Long): String? {
        var bits = bits
        if (-1000 < bits && bits < 1000) {
            return "${bits}b"
        }
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bits <= -999950 || bits >= 999950) {
            bits /= 1000
            ci.next()
        }
        return java.lang.String.format("%.0f%cb", bits / 1000.0, ci.current())
    }
}

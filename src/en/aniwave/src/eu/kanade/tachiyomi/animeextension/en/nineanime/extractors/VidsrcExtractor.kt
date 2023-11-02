package eu.kanade.tachiyomi.animeextension.en.nineanime.extractors

import eu.kanade.tachiyomi.animeextension.en.nineanime.AniwaveUtils
import eu.kanade.tachiyomi.animeextension.en.nineanime.MediaResponseBody
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class VidsrcExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    private val utils by lazy { AniwaveUtils(client, headers) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(embedLink: String, name: String, type: String): List<Video> {
        val vidId = embedLink.substringAfterLast("/").substringBefore("?")
        val (serverName, action) = when (name) {
            "vidplay" -> Pair("VidPlay", "rawVizcloud")
            "mycloud" -> Pair("MyCloud", "rawMcloud")
            else -> return emptyList()
        }
        val rawURL = utils.callEnimax(vidId, action) + "?${embedLink.substringAfter("?")}"
        val rawReferer = Headers.headersOf(
            "referer",
            "$embedLink&autostart=true",
            "x-requested-with",
            "XMLHttpRequest",
        )
        val rawResponse = client.newCall(GET(rawURL, rawReferer)).execute().parseAs<MediaResponseBody>()
        val playlistUrl = rawResponse.result.sources.first().file
            .replace("#.mp4", "")

        return playlistUtils.extractFromHls(
            playlistUrl,
            referer = "https://${embedLink.toHttpUrl().host}/",
            videoNameGen = { q -> "$serverName - $type - $q" },
            subtitleList = rawResponse.result.tracks.toTracks(),
        )
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    private fun List<MediaResponseBody.Result.SubTrack>.toTracks(): List<Track> {
        return filter {
            it.kind == "captions"
        }.mapNotNull {
            runCatching {
                Track(
                    it.file,
                    it.label,
                )
            }.getOrNull()
        }
    }
}

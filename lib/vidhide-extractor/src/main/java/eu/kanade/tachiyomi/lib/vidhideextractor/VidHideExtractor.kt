package eu.kanade.tachiyomi.lib.vidhideextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidHideExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidHide - $quality" }): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute()
            .asJsoup()

        val scriptBody = doc.selectFirst("script:containsData(m3u8)")
            ?.data()
            ?: return emptyList()

        val masterUrl = scriptBody
            .substringAfter("source", "")
            .substringAfter("file:\"", "")
            .substringBefore("\"", "")
            .takeIf(String::isNotBlank)
            ?: return emptyList()

        val subtitleList = try {
            val subtitleStr = scriptBody
                .substringAfter("tracks")
                .substringAfter("[")
                .substringBefore("]")
            val parsed = json.decodeFromString<List<TrackDto>>("[$subtitleStr]")
            parsed.filter { it.kind.equals("captions", true) }
                .map { Track(it.file, it.label!!) }
        } catch (e: SerializationException) {
            emptyList()
        }

        return playlistUtils.extractFromHls(
            masterUrl,
            url,
            videoNameGen = videoNameGen,
            subtitleList = subtitleList,
        )
    }

    @Serializable
    class TrackDto(
        val file: String,
        val kind: String,
        val label: String? = null,
    )
}

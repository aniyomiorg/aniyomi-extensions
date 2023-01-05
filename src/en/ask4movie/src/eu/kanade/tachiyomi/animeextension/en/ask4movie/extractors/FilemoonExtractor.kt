package eu.kanade.tachiyomi.animeextension.en.ask4movie.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Serializable
data class CaptionElement(
    val file: String,
    val label: String,
    val kind: String
)

class FilemoonExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String): List<Video> {
        try {
            val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)").data()

            val subtitleString = JsUnpacker(jsE).unpack().toString().substringAfter("fetch('").substringBefore("').")
            val subtitleTracks = mutableListOf<Track>()
            try {
                if (subtitleString.isNotEmpty()) {
                    val subResponse = client.newCall(
                        GET(subtitleString)
                    ).execute()

                    val subtitles = Json.decodeFromString<List<CaptionElement>>(subResponse.body!!.string())
                    for (sub in subtitles) {
                        subtitleTracks.add(Track(sub.file, sub.label))
                    }
                }
            } catch (e: Error) {}

            val masterUrl = JsUnpacker(jsE).unpack().toString().substringAfter("{file:\"").substringBefore("\"}")
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val videoList = mutableListOf<Video>()

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = "Filemoon:" + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")
                    try {
                        videoList.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleTracks))
                    } catch (e: Error) {
                        videoList.add(Video(videoUrl, quality, videoUrl))
                    }
                }
            return videoList
        } catch (e: Exception) {
            return emptyList()
        }
    }
}

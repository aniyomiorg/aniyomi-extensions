package eu.kanade.tachiyomi.animeextension.en.nineanime

import dev.datlag.jsunpacker.JsUnpacker
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
    val kind: String,
)

class FilemoonExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, prefix: String = "Filemoon"): List<Video> {
        try {
            val unpacked = client.newCall(GET(url)).execute().asJsoup().select("script:containsData(eval)").mapNotNull { element ->
                element?.data()
                    ?.let { JsUnpacker.unpackAndCombine(it) }
            }.first { it.contains("{file:") }

            val subtitleTracks = mutableListOf<Track>()
            if (unpacked.contains("fetch('")) {
                val subtitleString = unpacked.substringAfter("fetch('").substringBefore("').")

                try {
                    if (subtitleString.isNotEmpty()) {
                        val subResponse = client.newCall(
                            GET(subtitleString),
                        ).execute()

                        val subtitles = Json.decodeFromString<List<CaptionElement>>(subResponse.body.string())
                        for (sub in subtitles) {
                            subtitleTracks.add(Track(sub.file, sub.label))
                        }
                    }
                } catch (_: Error) {}
            }

            val masterUrl = unpacked.substringAfter("{file:\"").substringBefore("\"}")

            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()

            val videoList = mutableListOf<Video>()

            val subtitleRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")
            try {
                subtitleTracks.addAll(
                    subtitleRegex.findAll(masterPlaylist).map {
                        Track(
                            it.groupValues[2],
                            it.groupValues[1],
                        )
                    },
                )
            } catch (_: Error) {}

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = "$prefix " + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
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

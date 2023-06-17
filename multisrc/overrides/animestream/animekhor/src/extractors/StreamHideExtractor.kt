package eu.kanade.tachiyomi.animeextension.en.animekhor.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamHideExtractor(private val client: OkHttpClient, private val headers: Headers) {
    // from nineanime / ask4movie FilemoonExtractor
    private val subtitleRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val page = client.newCall(GET(url, headers = headers)).execute().body.string()
        val unpacked = JsUnpacker.unpackAndCombine(page) ?: page
        val playlistUrl = unpacked.substringAfter("sources:")
            .substringAfter("file:\"")
            .substringBefore('"')

        val playlistData = client.newCall(GET(playlistUrl, headers = headers)).execute().body.string()

        val subs = subtitleRegex.findAll(playlistData).map {
            val subUrl = fixUrl(it.groupValues[2], playlistUrl)
            Track(subUrl, it.groupValues[1])
        }.toList()

        val separator = "#EXT-X-STREAM-INF"
        return playlistData.substringAfter(separator).split(separator).map {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringBefore("\n")
                .substringAfter("x")
                .substringBefore(",") + "p"

            val urlPart = it.substringAfter("\n").substringBefore("\n")
            val videoUrl = fixUrl(urlPart, playlistUrl)
            Video(videoUrl, "${prefix}StreamHide:$resolution", videoUrl, subtitleTracks = subs)
        }
    }

    private fun fixUrl(urlPart: String, playlistUrl: String) =
        when {
            !urlPart.startsWith("https:") -> playlistUrl.substringBeforeLast("/") + "/$urlPart"
            else -> urlPart
        }
}

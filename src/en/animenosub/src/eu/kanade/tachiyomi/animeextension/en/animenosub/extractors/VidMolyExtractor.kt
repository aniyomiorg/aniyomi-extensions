package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient) {

    private val regexPlaylist = Regex("file:\"(\\S+?)\"")

    fun getVideoList(url: String, lang: String): List<Video> {
        val body = client.newCall(GET(url)).execute()
            .body.string()
        val playlistUrl = regexPlaylist.find(body)!!.groupValues.get(1)
        val headers = Headers.headersOf("Referer", "https://vidmoly.to")
        val playlistData = client.newCall(GET(playlistUrl, headers)).execute()
            .body.string()

        val separator = "#EXT-X-STREAM-INF:"
        return playlistData.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, "$lang - $quality", videoUrl, headers)
        }
    }
}

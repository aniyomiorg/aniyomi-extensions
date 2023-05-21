package eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class JMVStreamExtractor(private val client: OkHttpClient) {

    private val regexPlaylist = Regex("src\":\"(\\S+?)\"")
    private val playerName = "JMVStream"

    fun videosFromUrl(iframeUrl: String): List<Video> {
        val iframeBody = client.newCall(GET(iframeUrl)).execute()
            .use { it.body.string() }

        val playlistUrl = regexPlaylist.find(iframeBody)!!.groupValues.get(1)
        val playlistData = client.newCall(GET(playlistUrl)).execute()
            .use { it.body.string() }

        val separator = "#EXT-X-STREAM-INF:"
        return playlistData.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore("\n")
                .substringBefore(",") + "p"
            val path = it.substringAfter("\n").substringBefore("\n")
            val url = playlistUrl.replace("playlist.m3u8", path)
            Video(url, "$playerName - $quality", url)
        }
    }
}

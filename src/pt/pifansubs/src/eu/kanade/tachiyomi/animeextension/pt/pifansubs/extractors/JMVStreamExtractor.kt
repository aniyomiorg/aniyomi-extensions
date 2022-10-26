package eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class JMVStreamExtractor(private val client: OkHttpClient) {

    private val REGEX_PLAYLIST = Regex("src\":\"(\\S+?)\"")
    private val PLAYER_NAME = "JMVStream"

    fun videosFromUrl(iframeUrl: String): List<Video> {
        val iframeBody = client.newCall(GET(iframeUrl)).execute()
            .body!!.string()
        val playlistUrl = REGEX_PLAYLIST.find(iframeBody)!!.groupValues.get(1)
        val playlistData = client.newCall(GET(playlistUrl)).execute()
            .body!!.string()

        val separator = "#EXT-X-STREAM-INF:"
        return playlistData.substringAfter(separator).split(separator).map {
            val quality = it.substringAfterLast("x") + "p"
            val path = it.substringAfter("\n").substringBefore("\n")
            val url = playlistUrl.replace("playlist.m3u8", path)
            Video(url, "$PLAYER_NAME - $quality", url)
        }
    }
}

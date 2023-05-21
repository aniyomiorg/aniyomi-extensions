package eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class GoAnimesExtractor(private val client: OkHttpClient) {
    private val regexPlayer = Regex("""player\('(\S+?)','\S+'\)""")

    fun videosFromUrl(url: String): List<Video> {
        val playlistUrl = client.newCall(GET(url)).execute()
            .body.string()
            .let(JsUnpacker::unpack)
            .let(regexPlayer::find)
            ?.groupValues
            ?.get(1)
            ?: return emptyList<Video>()

        val playlistData = client.newCall(GET(playlistUrl)).execute()
            .body.string()

        val separator = "#EXT-X-STREAM-INF:"
        return playlistData.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, "GoAnimes - $quality", videoUrl)
        }
    }
}

package eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class StreamHideExtractor(private val client: OkHttpClient) {
    // from nineanime / ask4movie FilemoonExtractor
    private val subtitleRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")

    fun videosFromUrl(url: String, name: String): List<Video> {
        val page = client.newCall(GET(url)).execute().body.string()
        val unpacked = JsUnpacker(page).unpack() ?: return emptyList()
        val playlistUrl = unpacked.substringAfter("sources:")
            .substringAfter("file:\"") // StreamHide
            .substringAfter("src:\"") // StreamVid
            .substringBefore('"')

        val playlistData = client.newCall(GET(playlistUrl)).execute().body.string()

        val subs = subtitleRegex.findAll(playlistData).map {
            val urlPart = it.groupValues[2]
            val subUrl = when {
                !urlPart.startsWith("https:") ->
                    playlistUrl.substringBeforeLast("/") + "/$urlPart"
                else -> urlPart
            }
            Track(subUrl, it.groupValues[1])
        }.toList()

        // The playlist usually only have one video quality.
        return listOf(Video(playlistUrl, name, playlistUrl, subtitleTracks = subs))
    }
}

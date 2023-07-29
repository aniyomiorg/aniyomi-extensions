package eu.kanade.tachiyomi.animeextension.fr.jetanime.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SentinelExtractor(private val client: OkHttpClient) {

    private val playListUtils: PlaylistUtils by lazy {
        PlaylistUtils(client)
    }

    fun videoFromUrl(url: String, name: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()

        val script = document.selectFirst("script:containsData(m3u8),script:containsData(mp4)")
            ?.data()
            ?.let { t -> JsUnpacker.unpackAndCombine(t) ?: t }
            ?: return emptyList()

        val videoUrl = Regex("""file: ?\"(.*?(?:m3u8|mp4).*?)\"""").find(script)!!.groupValues[1]
        val subtitleList = Regex("""file: ?\"(.*?(?:vtt|ass|srt).*?)\".*?label: ?\"(.*?)\"""").find(script)?.let {
            listOf(Track(it.groupValues[1], it.groupValues[2]))
        } ?: emptyList()

        if (videoUrl.toHttpUrlOrNull() == null) {
            return emptyList()
        }

        return when {
            videoUrl.contains(".m3u8") -> playListUtils.extractFromHls(videoUrl, url, videoNameGen = { quality -> "Sentinel: $quality ($name)" }, subtitleList = subtitleList)
            else -> {
                listOf(
                    Video(videoUrl, "Sentinel: Video ($name)", videoUrl, subtitleTracks = subtitleList),
                )
            }
        }
    }
}

package eu.kanade.tachiyomi.animeextension.fr.otakufr.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidbmExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers = headers)).execute().asJsoup()
        val js = doc.selectFirst("script:containsData(m3u8),script:containsData(mp4)")?.data() ?: return emptyList()

        val masterUrl = js.substringAfter("source")
            .substringAfter("file:\"")
            .substringBefore("\"")

        val quality = js.substringAfter("source")
            .substringAfter("file")
            .substringBefore("]")
            .substringAfter("label:\"")
            .substringBefore("\"")

        return if (masterUrl.contains("m3u8")) {
            PlaylistUtils(client, headers).extractFromHls(masterUrl, videoNameGen = { quality -> "Vidbm - $quality" })
        } else {
            listOf(Video(masterUrl, "Vidbm - $quality", masterUrl))
        }
    }
}

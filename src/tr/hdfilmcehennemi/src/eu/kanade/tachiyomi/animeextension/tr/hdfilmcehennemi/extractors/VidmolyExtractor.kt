package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidmolyExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).await()
            .body.string()

        val playlistUrl = body.substringAfter("file:\"", "").substringBefore('"', "")
            .takeIf(String::isNotBlank)
            ?: return emptyList()

        return playlistUtils.extractFromHls(playlistUrl, url, videoNameGen = { "Vidmoly - $it" })
    }
}

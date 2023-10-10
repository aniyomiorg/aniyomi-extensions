package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class RapidrameExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().use { it.asJsoup() }
        val script = doc.selectFirst("script:containsData(eval):containsData(file_link)")
            ?.data()
            ?.let(Unpacker::unpack)
            ?.takeIf(String::isNotEmpty)
            ?: return emptyList()

        val playlistUrl = script.substringAfter('"').substringBefore('"')
            .let { String(Base64.decode(it, Base64.DEFAULT)) }

        return playlistUtils.extractFromHls(playlistUrl, url, videoNameGen = { "Rapidrame - $it" })
    }
}

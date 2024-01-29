package eu.kanade.tachiyomi.animeextension.hi.yomovies.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class MinoplresExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val newHeaders = headers.newBuilder().set("Referer", url).build()
        val doc = client.newCall(GET(url, newHeaders)).execute()
            .asJsoup()
        val script = doc.selectFirst("script:containsData(sources:)")?.data()
            ?: return emptyList()

        val masterUrl = script.substringAfter("file:\"").substringBefore('"')
        return playlistUtils.extractFromHls(
            masterUrl,
            referer = url,
            videoNameGen = { "$name Minoplres - $it" },
        )
    }
}

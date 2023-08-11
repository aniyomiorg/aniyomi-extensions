package eu.kanade.tachiyomi.animeextension.hi.yomovies.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class SpeedostreamExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, referer: String, prefix: String = ""): List<Video> {
        val docHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .add("Referer", referer)
            .build()

        val document = client.newCall(
            GET(url, headers = docHeaders),
        ).execute().asJsoup()

        val masterUrl = document.selectFirst("script:containsData(file:)")
            ?.data()
            ?.substringAfter("file:")
            ?.substringAfter("\"")
            ?.substringBefore("\"")
            ?: return emptyList()

        return PlaylistUtils(client, headers).extractFromHls(
            masterUrl,
            referer = "https://${url.toHttpUrl().host}/",
            videoNameGen = { "${prefix}Speedostream - $it" },
        )
    }
}

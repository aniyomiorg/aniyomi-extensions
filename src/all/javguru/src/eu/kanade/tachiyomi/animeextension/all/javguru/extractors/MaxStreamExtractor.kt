package eu.kanade.tachiyomi.animeextension.all.javguru.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class MaxStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playListUtils by lazy { PlaylistUtils(client, headers) }

    fun videoFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()

        val script = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val videoUrl = script.substringAfter("file:\"").substringBefore("\"")

        if (videoUrl.toHttpUrlOrNull() == null) {
            return emptyList()
        }

        return playListUtils.extractFromHls(videoUrl, url, videoNameGen = { quality -> "MaxStream: $quality" })
    }
}

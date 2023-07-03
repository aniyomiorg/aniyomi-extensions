package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class WolfstreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val url = client.newCall(
            GET(url),
        ).execute().asJsoup().selectFirst("script:containsData(sources)")?.let {
            it.data().substringAfter("{file:\"").substringBefore("\"")
        } ?: return emptyList()
        return listOf(
            Video(url, "${prefix}WolfStream", url),
        )
    }
}

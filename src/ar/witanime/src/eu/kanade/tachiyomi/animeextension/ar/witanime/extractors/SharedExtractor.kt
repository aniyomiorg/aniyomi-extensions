package eu.kanade.tachiyomi.animeextension.ar.witanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class SharedExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): Video {
        val document = client.newCall(GET(url)).execute().asJsoup()
        /*val videoList = mutableListOf<Video>()
        val src = document.select("source > src")
        val video = Video(src, "4Shared", src, null)
        videoList.add(video)
        return videoList*/
        // val vidSource = 4Shared.select("source")
        return Video(document.attr("source > src"), "4Shared", document.attr("source > src"), null)
    }
}

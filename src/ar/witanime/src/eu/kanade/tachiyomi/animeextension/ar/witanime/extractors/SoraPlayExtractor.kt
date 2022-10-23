package eu.kanade.tachiyomi.animeextension.ar.witanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class SoraPlayExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, newHeaders: Headers): List<Video> {
        val document = client.newCall(GET(url, newHeaders)).execute().asJsoup()

        // val videoList = mutableListOf<Video>()
        /*val script = element.select("script")
            .firstOrNull { it.data().contains("sources:") }*/

        val data = document.data().substringAfter("sources: [").substringBefore("],")
        val sources = data.split("\"file\":\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringBefore("\"")
            val quality = "Soraplay: " + source.substringAfter("\"label\":\"").substringBefore("\"") // .substringAfter("format: '")
            val video = Video(src, quality, src, headers = newHeaders)
            videoList.add(video)
        }
        return videoList
    }
}

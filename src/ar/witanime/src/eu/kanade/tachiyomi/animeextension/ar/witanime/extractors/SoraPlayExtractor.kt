package eu.kanade.tachiyomi.animeextension.ar.witanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class SoraPlayExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val newHeaders = headers.newBuilder().set("referer", "https://yonaplay.org/").build()
        val doc = client.newCall(GET(url, newHeaders)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")!!
        val data = script.data().substringAfter("sources: [").substringBefore("],")

        return data.split("\"file\":\"").drop(1).map { source ->
            val src = source.substringBefore("\"")
            val quality = "Soraplay: " + source.substringAfter("\"label\":\"").substringBefore("\"")
            Video(src, quality, src, headers = newHeaders)
        }
    }
}

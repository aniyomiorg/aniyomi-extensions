package eu.kanade.tachiyomi.animeextension.ar.mycima.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class GoVadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, host: String): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")!!
        val data = script.data().substringAfter("sources: [").substringBefore("],")
        return data.split("file:\"").drop(1).map { source ->
            val src = source.substringBefore("\"")
            val qualityHost = host.replaceFirstChar(Char::uppercase)
            var quality = source.substringAfter("label:\"").substringBefore("\"")
            if (quality.length > 15) { quality = "480p" }
            Video(src, "$qualityHost: $quality", src)
        }
    }
}

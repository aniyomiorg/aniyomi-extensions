package eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class AdoroDoramasExtractor(private val client: OkHttpClient) {

    private val playerName = "AdoroDoramas"

    fun videosFromUrl(url: String): List<Video> {
        val body = client.newCall(GET(url)).execute()
            .use { it.body.string() }
            .substringAfter("sources: [")
            .substringBefore("],")
        return body.split("}").filter { it.isNotBlank() }.map {
            val quality = it.substringAfter("size: ").substringBefore(" ") + "p"
            val videoUrl = it.substringAfter("src: '").substringBefore("'")
            Video(url, "$playerName - $quality", videoUrl)
        }
    }
}

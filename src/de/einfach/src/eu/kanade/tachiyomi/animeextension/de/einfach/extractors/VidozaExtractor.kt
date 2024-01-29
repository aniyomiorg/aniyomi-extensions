package eu.kanade.tachiyomi.animeextension.de.einfach.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VidozaExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url)).execute()
            .asJsoup()

        val script = doc.selectFirst("script:containsData(sourcesCode: [)")
            ?.data()
            ?: return emptyList()

        return script.substringAfter("sourcesCode: [").substringBefore("],")
            .split('{')
            .drop(1)
            .mapNotNull {
                val videoUrl = it.substringAfter("src: \"").substringBefore('"')
                val resolution = it.substringAfter("res:\"").substringBefore('"') + "p"
                Video(videoUrl, "Vidoza - $resolution", videoUrl)
            }
    }
}

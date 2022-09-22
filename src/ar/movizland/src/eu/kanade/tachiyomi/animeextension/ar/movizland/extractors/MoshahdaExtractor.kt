package eu.kanade.tachiyomi.animeextension.ar.movizland.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class MoshahdaExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(link: String, headers: Headers): List<Video> {
        val request = client.newCall(GET(link, headers)).execute().asJsoup()
        val element = request.selectFirst("script:containsData(sources)")
        val videoList = mutableListOf<Video>()
        val qualityMap = mapOf("l" to "240p", "n" to "360p", "h" to "480p", "x" to "720p", "o" to "1080p")
        val data2 = element.data().substringAfter(", file: \"").substringBefore("\"}],")
        val url = Regex("(.*)_,(.*),\\.urlset/master(.*)").find(data2)
        val sources = url!!.groupValues[2].split(",")
        for (quality in sources) {
            val src = url.groupValues[1] + "_" + quality + "/index-v1-a1" + url.groupValues[3]
            val video = qualityMap[quality]?.let {
                Video(src, "Moshahda: $it", src)
            }
            if (video != null) {
                videoList.add(video)
            }
        }
        return videoList
    }
}

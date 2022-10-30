package eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidYardExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val callPlayer = client.newCall(GET(url)).execute().body!!.string()
        val data = callPlayer.substringAfter("hls\":[").substringBefore("]")
        val sources = data.split("profile\":\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringAfter("url\":\"").substringBefore("\"")
            val quality = source.substringBefore("\"")
            val video = Video(src, quality, src, headers = headers)
            videoList.add(video)
        }
        return videoList
    }
}

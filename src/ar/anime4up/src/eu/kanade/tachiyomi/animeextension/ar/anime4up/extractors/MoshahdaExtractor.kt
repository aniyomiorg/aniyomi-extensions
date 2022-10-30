package eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class MoshahdaExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val callPlayer = client.newCall(GET(url, headers)).execute().asJsoup()
        Log.i("embeddd", "$callPlayer")

        val data = callPlayer.data().substringAfter("sources: [").substringBefore("],")
        val sources = data.split("file: \"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val masterUrl = source.substringBefore("\"}")
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = it.substringAfter("RESOLUTION=").substringAfter("x")
                        .substringBefore(",") + "p"
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")
                    videoList.add(Video(videoUrl, quality, videoUrl))
                }
            return videoList
        }
        return videoList
    }
}

package eu.kanade.tachiyomi.animeextension.ar.witanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class DailymotionExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, newHeaders: Headers): List<Video> {
        val id = url.split("/").last()
        val document = client.newCall(GET("https://www.dailymotion.com/player/metadata/video/$id", newHeaders)).execute().asJsoup()
        val streamURL = document.body().toString().substringAfter("{\"type\":\"application\\/x-mpegURL\",\"url\":\"").substringBefore("\"").replace("\\", "")
        val sources = client.newCall(GET(streamURL)).execute().asJsoup().body().toString()
        val videoList = mutableListOf<Video>()
        val videos = Regex("#EXT(?:.*?)NAME=\"(.*?)\",PROGRESSIVE-URI=\"(.*?)\"").findAll(sources).map {
            Video(it.groupValues[2], "Dailymotion: ${it.groupValues[1]}", it.groupValues[2])
        }
        videoList.addAll(videos)
        return videoList
    }
}

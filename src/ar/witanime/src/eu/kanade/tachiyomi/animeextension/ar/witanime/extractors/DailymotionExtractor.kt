package eu.kanade.tachiyomi.animeextension.ar.witanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DailymotionExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val id = url.split("/").last()
        val request = GET("https://www.dailymotion.com/player/metadata/video/$id", headers)
        val body = client.newCall(request).execute().body.string()
        val streamURL = body.substringAfter("{\"type\":\"application\\/x-mpegURL\",\"url\":\"")
            .substringBefore("\"")
            .replace("\\", "")

        val sources = client.newCall(GET(streamURL)).execute().body.string()
        return Regex("#EXT(?:.*?)NAME=\"(.*?)\",PROGRESSIVE-URI=\"(.*?)\"").findAll(sources).map {
            Video(it.groupValues[2], "Dailymotion: ${it.groupValues[1]}p", it.groupValues[2])
        }.toList()
    }
}

package eu.kanade.tachiyomi.animeextension.ar.asia2tv.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

class FembedExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val videoApi = url.replace("/v/", "/api/source/")
        // val jsonR = Json.decodeFromString<JSONObject>(client.newCall(POST(videoApi)).execute().body!!.string())
        /*val jsonR = Json.decodeFromString<JsonObject>(
            Jsoup.connect(videoApi).ignoreContentType(true)
                .execute().body()
        )*/
        val jsonR = Json.decodeFromString<JsonObject>(
            client.newCall(POST(videoApi)).execute().body!!.string()
        )

        val videoList = mutableListOf<Video>()
        if (jsonR["success"].toString() == "true") {
            jsonR["data"]!!.jsonArray.forEach() {
                val videoUrl = it.jsonObject["file"].toString().trim('"')
                val quality = "Fembed:" + it.jsonObject["label"].toString().trim('"')
                videoList.add(Video(videoUrl, quality, videoUrl))
            }
            return videoList
        } else {
            val videoUrl = "not used"
            val quality = "Video taken down for dmca"
            videoList.add(Video(videoUrl, quality, videoUrl))
        }
        return videoList
    }
}

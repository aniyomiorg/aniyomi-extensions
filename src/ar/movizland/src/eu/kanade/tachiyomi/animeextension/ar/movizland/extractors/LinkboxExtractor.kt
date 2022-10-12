package eu.kanade.tachiyomi.animeextension.ar.movizland.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

class LinkboxExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val id = url.substringAfter("?id=")
        val request = client.newCall(GET("https://www.linkbox.to/api/open/get_url?itemId=$id")).execute().asJsoup()
        val responseJson = Json.decodeFromString<JsonObject>(request.select("body").text())
        val data = responseJson["data"]?.jsonObject
        val resolutions = data!!.jsonObject["rList"]!!.jsonArray
        resolutions.map {
            videoList.add(
                Video(
                    it.jsonObject["url"].toString().replace("\"", ""),
                    "Linkbox: ${it.jsonObject["resolution"].toString().replace("\"", "")}",
                    it.jsonObject["url"].toString().replace("\"", "")
                )
            )
        }
        return videoList
    }
}

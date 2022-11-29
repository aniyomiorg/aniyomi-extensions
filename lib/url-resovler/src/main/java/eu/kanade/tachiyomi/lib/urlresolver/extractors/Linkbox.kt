package eu.kanade.tachiyomi.lib.urlresolver.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

class Linkbox(private val client: OkHttpClient) {
    fun extract(url: String): List<Video> {
        val body = runCatching {
            client.newCall(GET(url)).execute().body?.string().orEmpty()
        }.getOrNull() ?: return emptyList<Video>()
        val responseJson = Json.decodeFromString<JsonObject>(body)
        val data = responseJson["data"]?.jsonObject
        val resolutions = data!!.jsonObject["rList"]!!.jsonArray
        return resolutions.map {
            Video(
                it.jsonObject["url"].toString().removeSurrounding("\""),
                "Linkbox: ${it.jsonObject["resolution"].toString().removeSurrounding("\"")}",
                it.jsonObject["url"].toString().removeSurrounding("\"")
            )
        }
    }
}

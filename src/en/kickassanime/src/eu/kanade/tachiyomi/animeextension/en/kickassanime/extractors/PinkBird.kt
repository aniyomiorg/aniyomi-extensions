package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.lang.Exception

@ExperimentalSerializationApi
class PinkBird(private val client: OkHttpClient, private val json: Json) {
    fun videosFromUrl(serverUrl: String, server: String): List<Video> {
        return try {
            val apiLink = serverUrl.replace("player.php", "pref.php")
            val resp = client.newCall(GET(apiLink)).execute()
            val jsonResp = json.decodeFromString<JsonObject>(resp.body.string())
            jsonResp["data"]!!.jsonArray.map { el ->
                val eid = el.jsonObject["eid"]!!.jsonPrimitive.content.decodeBase64()
                val response = client.newCall(GET("https://pb.kaast1.com/manifest/$eid/master.m3u8")).execute()
                if (response.code != 200) return emptyList()
                response.body.string().substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").map {
                        val quality = it.substringAfter("RESOLUTION=").split(",")[0].split("\n")[0].substringAfter("x") + "p $server"
                        var videoUrl = it.substringAfter("\n").substringBefore("\n")
                        if (videoUrl.startsWith("https").not()) {
                            videoUrl = "https://${response.request.url.host}$videoUrl"
                        }
                        Video(videoUrl, quality, videoUrl)
                    }
            }.flatten()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun String.decodeBase64(): String {
        return Base64.decode(this, Base64.DEFAULT).toString(Charsets.UTF_8)
    }
}

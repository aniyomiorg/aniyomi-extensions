package eu.kanade.tachiyomi.animeextension.en.bestdubbedanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class DailyMotionExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val htmlString = client.newCall(GET(url)).execute().body!!.string()

        val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
        val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
        val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

        val jsonUrl = "https://www.dailymotion.com/player/metadata/video/${url.toHttpUrl().encodedPath}?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val json = Json.decodeFromString<JsonObject>(
            client.newCall(GET(jsonUrl))
                .execute().body!!.string()
        )

        val masterUrl = json["qualities"]!!
            .jsonObject["auto"]!!
            .jsonArray[0]
            .jsonObject["url"]!!
            .jsonPrimitive.content

        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()

        val separator = "#EXT-X-STREAM-INF"
        masterPlaylist.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",NAME") + "p"
            var videoUrl = it.substringAfter("\n").substringBefore("\n")

            videoList.add(Video(videoUrl, "$quality (DM)", videoUrl))
        }

        return videoList
    }
}

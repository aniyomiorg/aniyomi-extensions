package eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class DailymotionExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val videoList = mutableListOf<Video>()
        val htmlString = client.newCall(GET(url)).execute().body.string()

        val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
        val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
        val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

        val jsonUrl = "https://www.dailymotion.com/player/metadata/video/${url.toHttpUrl().encodedPath}?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val parsed = json.decodeFromString<DailyQuality>(
            client.newCall(GET(jsonUrl))
                .execute().body.string(),
        )

        val masterUrl = parsed.qualities.auto.first().url

        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()

        val separator = "#EXT-X-STREAM-INF"
        masterPlaylist.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",NAME") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")

            val videoHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Host", videoUrl.toHttpUrl().host)
                .add("Origin", "https://www.dailymotion.com")
                .add("Referer", "https://www.dailymotion.com")
                .build()

            videoList.add(
                Video(videoUrl, "Dailymotion - $quality", videoUrl, headers = videoHeaders),
            )
        }

        return videoList
    }

    @Serializable
    data class DailyQuality(
        val qualities: Auto,
    ) {
        @Serializable
        data class Auto(
            val auto: List<Item>,
        ) {
            @Serializable
            data class Item(
                val type: String,
                val url: String,
            )
        }
    }
}

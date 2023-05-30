package eu.kanade.tachiyomi.animeextension.all.lmanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

@Serializable
data class DailyQuality(
    val qualities: Auto,
) {
    @Serializable
    data class Auto(
        val auto: List<Video>,
    ) {
        @Serializable
        data class Video(
            val type: String,
            val url: String,
        )
    }
}

class DailymotionExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val id = url.substringBefore("?").substringAfterLast("/")
        val jsonUrl = "https://www.dailymotion.com/player/metadata/video/$id"
        val jsonRequest = client.newCall(GET(jsonUrl)).execute().body.string()
        val parsed = json.decodeFromString<DailyQuality>(jsonRequest)

        val masterUrl = parsed.qualities.auto.first().url
        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()

        val separator = "#EXT-X-STREAM-INF"
        return masterPlaylist.substringAfter(separator).split(separator).map {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore(",NAME") + "p"
            val quality = "$prefix $resolution"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")

            Video(videoUrl, quality, videoUrl)
        }
    }
}

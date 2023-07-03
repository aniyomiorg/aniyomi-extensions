package eu.kanade.tachiyomi.animeextension.en.myanime.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

@Serializable
data class DailyQuality(
    val qualities: Auto,
    val subtitles: Subtitle? = null,
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

//    @Serializable
//    data class SubtitleObject(
//        val label: String,
//        val urls: List<String>,
//    )

    @Serializable
    data class Subtitle(
        // data can be either an empty list, or `Map<String, SubtitleObject>`
        val data: JsonElement? = null,
    )
}

class DailymotionExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = "Dailymotion - "): List<Video> {
        val videoList = mutableListOf<Video>()
        val htmlString = client.newCall(GET(url)).execute().body.string()

        val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
        val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
        val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

        val jsonUrl = "https://www.dailymotion.com/player/metadata/video${url.toHttpUrl().encodedPath}?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val parsed = json.decodeFromString<DailyQuality>(
            client.newCall(GET(jsonUrl))
                .execute().body.string(),
        )
        val subtitleList = mutableListOf<Track>()
//        if (parsed.subtitles != null) {
//            if (parsed.subtitles.data.toString() != "[]") {
//
//            }
//        }

        val masterUrl = parsed.qualities.auto.first().url

        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()

        val separator = "#EXT-X-STREAM-INF"
        masterPlaylist.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",NAME") + "p"
            var videoUrl = it.substringAfter("\n").substringBefore("\n")

            try {
                videoList.add(Video(videoUrl, prefix + quality, videoUrl, subtitleTracks = subtitleList))
            } catch (a: Exception) {
                videoList.add(Video(videoUrl, prefix + quality, videoUrl))
            }
        }

        return videoList
    }
}

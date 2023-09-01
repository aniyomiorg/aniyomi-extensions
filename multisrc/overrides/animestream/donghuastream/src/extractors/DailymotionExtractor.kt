package eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class DailymotionExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val htmlString = client.newCall(GET(url)).execute().use { it.body.string() }

        val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
        val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
        val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

        val videoQuery = url.toHttpUrl().queryParameter("video") ?: url.toHttpUrl().encodedPath

        val jsonUrl = "https://www.dailymotion.com/player/metadata/video/$videoQuery?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val parsed = client.newCall(GET(jsonUrl)).execute().parseAs<DailyQuality>()

        val subtitleList = parsed.subtitles?.data?.map { k ->
            Track(
                k.value.urls.first(),
                k.value.label,
            )
        } ?: emptyList()

        val masterUrl = parsed.qualities.auto.first().url

        return playlistUtils.extractFromHls(masterUrl, subtitleList = subtitleList, videoNameGen = { q -> "$prefix$q" })
    }

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

        @Serializable
        data class Subtitle(
            val data: Map<String, SubtitleObject>,
        ) {
            @Serializable
            data class SubtitleObject(
                val label: String,
                val urls: List<String>,
            )
        }
    }

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { it }): T {
        val responseBody = use { transform(it.body.string()) }
        return json.decodeFromString(responseBody)
    }
}

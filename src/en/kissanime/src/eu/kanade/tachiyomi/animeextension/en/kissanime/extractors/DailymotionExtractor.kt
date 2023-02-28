package eu.kanade.tachiyomi.animeextension.en.kissanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class DailymotionExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String, baseUrl: String, password: String?): List<Video> {
        val videoList = mutableListOf<Video>()
        val htmlString = client.newCall(GET(url)).execute().body.string()

        val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
        val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
        val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

        val jsonUrl = "https://www.dailymotion.com/player/metadata/video${url.toHttpUrl().encodedPath}?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"

        var parsed = json.decodeFromString<DailyQuality>(
            client.newCall(GET(jsonUrl))
                .execute().body.string(),
        )
        var playlistHeaders = Headers.headersOf()
        val videoHeaders = Headers.headersOf(
            "Accept",
            "*/*",
            "Origin",
            "https://www.dailymotion.com",
            "Referer",
            "https://www.dailymotion.com/",
        )

        if (parsed.error != null) {
            if (parsed.error!!.type == "password_protected") {
                val postUrl = "https://graphql.api.dailymotion.com/oauth/token"
                val clientId = htmlString.substringAfter("client_id\":\"").substringBefore("\"")
                val clientSecret = htmlString.substringAfter("client_secret\":\"").substringBefore("\"")
                val scope = htmlString.substringAfter("client_scope\":\"").substringBefore("\"")

                val tokenHeaders = Headers.headersOf(
                    "Accept",
                    "application/json, text/plain, */*",
                    "Content-Type",
                    "application/x-www-form-urlencoded",
                    "Origin",
                    "https://www.dailymotion.com",
                    "Referer",
                    "https://www.dailymotion.com/",
                )
                val tokenBody = "client_id=$clientId&client_secret=$clientSecret&traffic_segment=$ts&visitor_id=$v1st&grant_type=client_credentials&scope=$scope".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val tokenResponse = client.newCall(
                    POST(postUrl, body = tokenBody, headers = tokenHeaders),
                ).execute()
                val tokenParsed = json.decodeFromString<TokenResponse>(tokenResponse.body.string())

                val idUrl = "https://graphql.api.dailymotion.com/"
                val idHeaders = Headers.headersOf(
                    "Accept", "application/json, text/plain, */*",
                    "Authorization", "${tokenParsed.token_type} ${tokenParsed.access_token}",
                    "Content-Type", "application/json",
                    "Origin", "https://www.dailymotion.com",
                    "Referer", "https://www.dailymotion.com/",
                )

                val idData = """
                    {
                       "query":"query playerPasswordQuery(${'$'}videoId:String!,${'$'}password:String!){video(xid:${'$'}videoId,password:${'$'}password){id xid}}",
                       "variables":{
                          "videoId":"${parsed.id!!}",
                          "password":"$password"
                       }
                    }
                """.trimIndent().toRequestBody("application/json".toMediaType())

                val idResponse = client.newCall(
                    POST(idUrl, body = idData, headers = idHeaders),
                ).execute()
                val idParsed = json.decodeFromString<ProtectedResponse>(idResponse.body.string()).data.video

                val dmvk = htmlString.substringAfter("\"dmvk\":\"").substringBefore("\"")
                val getVideoIdUrl = "https://www.dailymotion.com/player/metadata/video/${idParsed.xid}?embedder=${"$baseUrl/"}&locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
                val getVideoIdHeaders = Headers.headersOf(
                    "Accept",
                    "*/*",
                    "Cookie",
                    "dmvk=$dmvk; ts=$ts; v1st=$v1st; usprivacy=1---; client_token=${tokenParsed.access_token}",
                    "Referer",
                    url,
                )
                playlistHeaders = Headers.headersOf(
                    "Accept",
                    "*/*",
                    "Cookie",
                    "dmvk=$dmvk; ts=$ts; v1st=$v1st; usprivacy=1---; client_token=${tokenParsed.access_token}",
                    "Referer",
                    url,
                )
                val getVideoIdResponse = client.newCall(GET(getVideoIdUrl, headers = getVideoIdHeaders)).execute()
                val videoQualityBody = getVideoIdResponse.body.string()
                parsed = json.decodeFromString<DailyQuality>(videoQualityBody)
            }
        }

        val masterUrl = parsed.qualities!!.auto.first().url

        val masterPlaylist = client.newCall(GET(masterUrl, headers = playlistHeaders)).execute().body.string()

        val separator = "#EXT-X-STREAM-INF"
        masterPlaylist.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",NAME") + "p"
            var videoUrl = it.substringAfter("\n").substringBefore("\n")

            videoList.add(Video(videoUrl, prefix + quality, videoUrl, headers = videoHeaders))
        }

        return videoList
    }

    @Serializable
    data class TokenResponse(
        val access_token: String,
        val token_type: String,
    )

    @Serializable
    data class ProtectedResponse(
        val data: DataObject,
    ) {
        @Serializable
        data class DataObject(
            val video: VideoObject,
        ) {
            @Serializable
            data class VideoObject(
                val id: String,
                val xid: String,
            )
        }
    }

    @Serializable
    data class DailyQuality(
        val error: Error? = null,
        val id: String? = null,
        val qualities: Auto? = null,
    ) {
        @Serializable
        data class Error(
            val type: String,
        )

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

package eu.kanade.tachiyomi.lib.dailymotionextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class DailymotionExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val DAILYMOTION_URL = "https://www.dailymotion.com"
        private const val GRAPHQL_URL = "https://graphql.api.dailymotion.com"
    }

    private fun headersBuilder(block: Headers.Builder.() -> Unit = {}) = headers.newBuilder()
        .add("Accept", "*/*")
        .set("Referer", "$DAILYMOTION_URL/")
        .set("Origin", DAILYMOTION_URL)
        .apply { block() }
        .build()

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = "Dailymotion - ", baseUrl: String = "", password: String? = null): List<Video> {
        val htmlString = client.newCall(GET(url)).execute().body.string()

        val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
        val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
        val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

        val videoQuery = url.toHttpUrl().run {
            queryParameter("video") ?: pathSegments.last()
        }

        val jsonUrl = "$DAILYMOTION_URL/player/metadata/video/$videoQuery?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val parsed = client.newCall(GET(jsonUrl)).execute().parseAs<DailyQuality>()

        return when {
            parsed.qualities != null && parsed.error == null -> videosFromDailyResponse(parsed, prefix)
            parsed.error?.type == "password_protected" && parsed.id != null -> {
                videosFromProtectedUrl(url, prefix, parsed.id, htmlString, ts, v1st, baseUrl, password)
            }
            else -> emptyList()
        }
    }

    private fun videosFromProtectedUrl(
        url: String,
        prefix: String,
        videoId: String,
        htmlString: String,
        ts: String,
        v1st: String,
        baseUrl: String,
        password: String?,
    ): List<Video> {
        val postUrl = "$GRAPHQL_URL/oauth/token"
        val clientId = htmlString.substringAfter("client_id\":\"").substringBefore('"')
        val clientSecret = htmlString.substringAfter("client_secret\":\"").substringBefore('"')
        val scope = htmlString.substringAfter("client_scope\":\"").substringBefore('"')

        val tokenBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("traffic_segment", ts)
            .add("visitor_id", v1st)
            .add("grant_type", "client_credentials")
            .add("scope", scope)
            .build()

        val tokenResponse = client.newCall(POST(postUrl, headersBuilder(), tokenBody)).execute()
        val tokenParsed = tokenResponse.parseAs<TokenResponse>()

        val idUrl = "$GRAPHQL_URL/"
        val idHeaders = headersBuilder {
            set("Accept", "application/json, text/plain, */*")
            add("Authorization", "${tokenParsed.token_type} ${tokenParsed.access_token}")
        }

        val idData = """
            {
               "query":"query playerPasswordQuery(${'$'}videoId:String!,${'$'}password:String!){video(xid:${'$'}videoId,password:${'$'}password){id xid}}",
               "variables":{
                  "videoId":"$videoId",
                  "password":"$password"
               }
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val idResponse = client.newCall(POST(idUrl, idHeaders, idData)).execute()
        val idParsed = idResponse.parseAs<ProtectedResponse>().data.video

        val dmvk = htmlString.substringAfter("\"dmvk\":\"").substringBefore('"')
        val getVideoIdUrl = "$DAILYMOTION_URL/player/metadata/video/${idParsed.xid}?embedder=${"$baseUrl/"}&locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val getVideoIdHeaders = headersBuilder {
            add("Cookie", "dmvk=$dmvk; ts=$ts; v1st=$v1st; usprivacy=1---; client_token=${tokenParsed.access_token}")
            set("Referer", url)
        }

        val parsed = client.newCall(GET(getVideoIdUrl, getVideoIdHeaders)).execute()
            .parseAs<DailyQuality>()

        return videosFromDailyResponse(parsed, prefix, getVideoIdHeaders)
    }

    private fun videosFromDailyResponse(parsed: DailyQuality, prefix: String, playlistHeaders: Headers? = null): List<Video> {
        val masterUrl = parsed.qualities?.auto?.firstOrNull()?.url
            ?: return emptyList<Video>()

        val subtitleList = parsed.subtitles?.data?.map {
            Track(it.urls.first(), it.label)
        } ?: emptyList<Track>()

        val masterHeaders = playlistHeaders ?: headersBuilder()

        return playlistUtils.extractFromHls(
            masterUrl,
            masterHeadersGen = { _, _ -> masterHeaders },
            subtitleList = subtitleList,
            videoNameGen = { "$prefix$it" },
        )
    }
}

package eu.kanade.tachiyomi.animeextension.en.holamovies.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class GDFlixExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    fun videosFromUrl(serverUrl: String): List<Video> {
        val videoList = mutableListOf<Video>()

        videoList.addAll(
            listOf("direct", "drivebot").parallelMap { type ->
                runCatching {
                    when (type) {
                        "direct" -> {
                            extractGDriveLink(serverUrl)
                        }
                        "drivebot" -> {
                            extractDriveBotLink(serverUrl)
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        return videoList
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun extractGDriveLink(mediaUrl: String): List<Video> {
        val tokenClient = client.newBuilder().addInterceptor(TokenInterceptor()).build()

        val response = tokenClient.newCall(GET(mediaUrl)).execute().asJsoup()

        val gdBtn = response.selectFirst("div.card-body a.btn")!!
        val gdLink = gdBtn.attr("href")

        return GoogleDriveExtractor(client, headers).videosFromUrl(gdLink, "Gdrive")
    }

    private fun extractDriveBotLink(mediaUrl: String): List<Video> {
        val response = client.newCall(GET(mediaUrl)).execute().asJsoup()
        val flixUrlPath = response.selectFirst("script:containsData(file)")?.data() ?: return emptyList()
        val flixUrl = "https://${mediaUrl.toHttpUrl().host}${flixUrlPath.substringAfter("replace(\"").substringBefore("\"")}"
        val flixDocument = client.newCall(GET(flixUrl)).execute().asJsoup()

        val driveBotUrl = flixDocument.selectFirst("div.card-body a.btn[href~=drivebot]")?.attr("href") ?: return emptyList()

        val docHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", driveBotUrl.toHttpUrl().host)
            .build()

        val documentResp = OkHttpClient().newCall(
            GET(driveBotUrl, headers = docHeaders),
        ).execute()

        val document = documentResp.asJsoup()

        val sessId = documentResp.headers.firstOrNull {
            it.first.startsWith("set-cookie", true) && it.second.startsWith("PHPSESSID", true)
        }?.second?.substringBefore(";") ?: ""

        val script = document.selectFirst("script:containsData(token)")?.data() ?: return emptyList()

        val token = script.substringAfter("'token', '").substringBefore("'")
        val postUrl = "https://${driveBotUrl.toHttpUrl().host}${script.substringAfter("fetch('").substringBefore("'")}"

        val postHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Cookie", sessId)
            .add("Host", driveBotUrl.toHttpUrl().host)
            .add("Origin", "https://${driveBotUrl.toHttpUrl().host}")
            .add("Referer", mediaUrl)
            .add("Sec-Fetch-Site", "same-origin")
            .build()

        val postBody = FormBody.Builder()
            .addEncoded("token", token)
            .build()

        val postResp = OkHttpClient().newCall(
            POST(postUrl, body = postBody, headers = postHeaders),
        ).execute()

        val url = try {
            json.decodeFromString<DriveBotResp>(postResp.body.string()).url
        } catch (a: Exception) {
            return emptyList()
        }

        val videoHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .add("Referer", "https://${driveBotUrl.toHttpUrl().host}/")
            .build()

        return listOf(
            Video(url, "DriveBot", url, headers = videoHeaders),
        )
    }

    @Serializable
    data class DriveBotResp(
        val url: String,
    )
}

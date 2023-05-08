package eu.kanade.tachiyomi.animeextension.en.holamovies.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
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
        val failedMediaUrl = mutableListOf<Pair<String, String>>()

        if (serverUrl.toHttpUrl().encodedPath != "/404") {
            val (videos, mediaUrl) = extractVideo(EpUrl("Video", serverUrl, "Video"))
            if (videos.isEmpty()) failedMediaUrl.add(Pair(mediaUrl, "Video"))
            videoList.addAll(videos)
        }

        videoList.addAll(
            failedMediaUrl.mapNotNull { (url, quality) ->
                runCatching {
                    extractGDriveLink(url, quality)
                }.getOrNull()
            }.flatten(),
        )

        videoList.addAll(
            failedMediaUrl.mapNotNull { (url, quality) ->
                runCatching {
                    extractDriveBotLink(url)
                }.getOrNull()
            }.flatten(),
        )

        return videoList
    }

    private fun extractVideo(epUrl: EpUrl): Pair<List<Video>, String> {
        val videoList = mutableListOf<Video>()

        val qualityRegex = """(\d+)p""".toRegex()
        val matchResult = qualityRegex.find(epUrl.name)
        val quality = if (matchResult == null) {
            epUrl.quality
        } else {
            matchResult.groupValues[1]
        }

        for (type in 1..3) {
            videoList.addAll(
                extractWorkerLinks(epUrl.url, quality, type),
            )
        }
        return Pair(videoList, epUrl.url)
    }

    private val sizeRegex = "\\[((?:.(?!\\[))+)][ ]*\$".toRegex(RegexOption.IGNORE_CASE)

    private fun extractWorkerLinks(mediaUrl: String, quality: String, type: Int): List<Video> {
        val reqLink = mediaUrl.replace("/file/", "/wfile/") + "?type=$type"
        val resp = client.newCall(GET(reqLink)).execute().asJsoup()
        val sizeMatch = sizeRegex.find(resp.select("div.card-header").text().trim())
        val size = sizeMatch?.groups?.get(1)?.value?.let { " - $it" } ?: ""
        return resp.select("div.card-body div.mb-4 > a").mapIndexed { index, linkElement ->
            val link = linkElement.attr("href")
            val decodedLink = if (link.contains("workers.dev")) {
                link
            } else {
                String(Base64.decode(link.substringAfter("download?url="), Base64.DEFAULT))
            }

            Video(
                url = decodedLink,
                quality = "$quality - CF $type Worker ${index + 1}$size",
                videoUrl = decodedLink,
            )
        }
    }

    private fun extractGDriveLink(mediaUrl: String, quality: String): List<Video> {
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
    data class EpUrl(
        val quality: String,
        val url: String,
        val name: String,
    )

    @Serializable
    data class DriveBotResp(
        val url: String,
    )
}

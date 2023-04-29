package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class GoogleDriveExtractor(private val client: OkHttpClient, private val headers: Headers) {
    // Needs to be the form of `https://drive.google.com/uc?id=GOOGLEDRIVEITEMID`
    fun videosFromUrl(itemUrl: String, videoName: String = "Video"): List<Video> {
        val itemHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Connection", "keep-alive")
            .add("Cookie", getCookie(itemUrl))
            .add("Host", "drive.google.com")
            .build()

        val itemResponse = client.newCall(
            GET(itemUrl, headers = itemHeaders),
        ).execute()

        val noRedirectClient = OkHttpClient().newBuilder().followRedirects(false).build()
        val document = itemResponse.asJsoup()

        val itemSize = document.selectFirst("span.uc-name-size")?.let {
            " ${it.ownText().trim()} "
        } ?: ""
        val url = document.selectFirst("form#download-form")?.attr("action") ?: return emptyList()
        val redirectHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Connection", "keep-alive")
            .add("Content-Length", "0")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .add("Cookie", getCookie(url))
            .add("Host", "drive.google.com")
            .add("Origin", "https://drive.google.com")
            .add("Referer", url.substringBeforeLast("&at="))
            .build()

        val response = noRedirectClient.newCall(
            POST(url, headers = redirectHeaders, body = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())),
        ).execute()
        val redirected = response.headers["location"] ?: return listOf(Video(url, videoName + itemSize, url))

        val redirectedHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Connection", "keep-alive")
            .add("Host", redirected.toHttpUrl().host)
            .add("Referer", "https://drive.google.com/")
            .build()

        val redirectedResponseHeaders = noRedirectClient.newCall(
            GET(redirected, headers = redirectedHeaders),
        ).execute().headers
        val authCookie = redirectedResponseHeaders.firstOrNull {
            it.first == "set-cookie" && it.second.startsWith("AUTH_")
        }?.second?.substringBefore(";") ?: return listOf(Video(url, videoName + itemSize, url))
        val newRedirected = redirectedResponseHeaders["location"] ?: return listOf(Video(redirected, videoName + itemSize, redirected))

        val googleDriveRedirectHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Connection", "keep-alive")
            .add("Cookie", getCookie(newRedirected))
            .add("Host", "drive.google.com")
            .add("Referer", "https://drive.google.com/")
            .build()
        val googleDriveRedirectUrl = noRedirectClient.newCall(
            GET(newRedirected, headers = googleDriveRedirectHeaders),
        ).execute().headers["location"]!!

        val videoHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Connection", "keep-alive")
            .add("Cookie", authCookie)
            .add("Host", googleDriveRedirectUrl.toHttpUrl().host)
            .add("Referer", "https://drive.google.com/")
            .build()

        return listOf(
            Video(googleDriveRedirectUrl, videoName + itemSize, googleDriveRedirectUrl, headers = videoHeaders),
        )
    }

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }
}

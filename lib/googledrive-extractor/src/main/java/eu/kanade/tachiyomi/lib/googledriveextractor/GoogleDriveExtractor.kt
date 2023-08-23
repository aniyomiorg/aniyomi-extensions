package eu.kanade.tachiyomi.lib.googledriveextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okhttp3.OkHttpClient

class GoogleDriveExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val GOOGLE_DRIVE_HOST = "drive.google.com"
        private const val ACCEPT = "text/html,application/xhtml+xml," +
            "application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    // Default / headers for most requests
    private fun headersBuilder(block: Headers.Builder.() -> Unit) = headers.newBuilder()
        .set("Accept", ACCEPT)
        .set("Connection", "keep-alive")
        .set("Host", GOOGLE_DRIVE_HOST)
        .set("Origin", "https://$GOOGLE_DRIVE_HOST")
        .set("Referer", "https://$GOOGLE_DRIVE_HOST/")
        .apply { block() }
        .build()

    // Needs to be the form of `https://drive.google.com/uc?id=GOOGLEDRIVEITEMID`
    fun videosFromUrl(itemUrl: String, videoName: String = "Video"): List<Video> {
        val itemHeaders = headersBuilder {
            set("Accept", "*/*")
            set("Accept-Language", "en-US,en;q=0.5")
            add("Cookie", getCookie(itemUrl))
        }

        val document = client.newCall(GET(itemUrl, itemHeaders)).execute()
            .use { it.asJsoup() }

        val itemSize = document.selectFirst("span.uc-name-size")
            ?.let { " ${it.ownText().trim()} " }
            ?: ""

        val url = document.selectFirst("form#download-form")?.attr("action") ?: return emptyList()
        val redirectHeaders = headersBuilder {
            add("Cookie", getCookie(url))
            set("Referer", url.substringBeforeLast("&at="))
        }

        val response = noRedirectClient.newCall(
            POST(url, redirectHeaders, body = FormBody.Builder().build()),
        ).execute()

        val redirected = response.use { it.headers["location"] }
            ?: return listOf(Video(url, videoName + itemSize, url))

        val redirectedHeaders = headersBuilder {
            set("Host", redirected.toHttpUrl().host)
        }

        val redirectedResponseHeaders = noRedirectClient.newCall(
            GET(redirected, redirectedHeaders),
        ).execute().use { it.headers }

        val authCookie = redirectedResponseHeaders.firstOrNull {
            it.first == "set-cookie" && it.second.startsWith("AUTH_")
        }?.second?.substringBefore(";") ?: return listOf(Video(url, videoName + itemSize, url))

        val newRedirected = redirectedResponseHeaders["location"]
            ?: return listOf(Video(redirected, videoName + itemSize, redirected))

        val googleDriveRedirectHeaders = headersBuilder {
            add("Cookie", getCookie(newRedirected))
        }

        val googleDriveRedirectUrl = noRedirectClient.newCall(
            GET(newRedirected, googleDriveRedirectHeaders),
        ).execute().use { it.headers["location"]!! }

        val videoHeaders = headersBuilder {
            add("Cookie", authCookie)
            set("Host", googleDriveRedirectUrl.toHttpUrl().host)
        }

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

package eu.kanade.tachiyomi.lib.googledriveextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.commonEmptyRequestBody

class GoogleDriveExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }

    private val cookieList = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl())

    private val noRedirectClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build()

    fun videosFromUrl(itemUrl: String, videoName: String = "Video"): List<Video> {
        val cookieJar = GDriveCookieJar()

        cookieJar.saveFromResponse("https://drive.google.com".toHttpUrl(), cookieList)

        val docHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            add("Connection", "keep-alive")
            add("Cookie", cookieList.toStr())
            add("Host", "drive.google.com")
        }.build()

        val docResp = noRedirectClient.newCall(
            GET(itemUrl, headers = docHeaders),
        ).execute()

        if (docResp.isRedirect) {
            return videoFromRedirect(itemUrl, videoName, "", cookieJar)
        }

        val document = docResp.asJsoup()

        val itemSize = document.selectFirst("span.uc-name-size")
            ?.let { " ${it.ownText().trim()} " }
            ?: ""

        val downloadUrl = document.selectFirst("form#download-form")?.attr("action") ?: return emptyList()
        val postHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            add("Content-Type", "application/x-www-form-urlencoded")
            set("Cookie", client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).toStr())
            add("Host", "drive.google.com")
            add("Referer", "https://drive.google.com/")
        }.build()

        val newUrl = noRedirectClient.newCall(
            POST(downloadUrl, headers = postHeaders, body = commonEmptyRequestBody),
        ).execute().use { it.headers["location"] ?: downloadUrl }

        return videoFromRedirect(newUrl, videoName, itemSize, cookieJar)
    }

    private fun videoFromRedirect(
        downloadUrl: String,
        videoName: String,
        itemSize: String,
        cookieJar: GDriveCookieJar,
    ): List<Video> {
        var newUrl = downloadUrl

        val newHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            set("Cookie", cookieJar.loadForRequest(newUrl.toHttpUrl()).toStr())
            set("Host", newUrl.toHttpUrl().host)
            add("Referer", "https://drive.google.com/")
        }.build()

        var newResp = noRedirectClient.newCall(
            GET(newUrl, headers = newHeaders),
        ).execute()

        var redirectCounter = 1
        while (newResp.isRedirect && redirectCounter < 15) {
            val setCookies = newResp.headers("Set-Cookie").mapNotNull { Cookie.parse(newResp.request.url, it) }
            cookieJar.saveFromResponse(newResp.request.url, setCookies)

            newUrl = newResp.headers["location"]!!
            newResp.close()

            val newHeaders = headers.newBuilder().apply {
                add("Accept", ACCEPT)
                set("Cookie", cookieJar.loadForRequest(newUrl.toHttpUrl()).toStr())
                set("Host", newUrl.toHttpUrl().host)
                add("Referer", "https://drive.google.com/")
            }.build()

            newResp = noRedirectClient.newCall(
                GET(newUrl, headers = newHeaders),
            ).execute()
            redirectCounter += 1
        }

        val videoUrl = newResp.use { it.request.url }

        val videoHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            set("Cookie", cookieJar.loadForRequest(videoUrl).toStr())
            set("Host", videoUrl.host)
            add("Referer", "https://drive.google.com/")
        }.build()

        return listOf(
            Video(
                videoUrl.toString(),
                videoName + itemSize,
                videoUrl.toString(),
                headers = videoHeaders,
            ),
        )
    }

    private fun List<Cookie>.toStr(): String {
        return this.joinToString("; ") { "${it.name}=${it.value}" }
    }
}

class GDriveCookieJar : CookieJar {

    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    // Append rather than overwrite, what could go wrong?
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val oldCookies = (cookieStore[url.host] ?: emptyList()).filter { c ->
            !cookies.any { t -> c.name == t.name }
        }
        cookieStore[url.host] = (oldCookies + cookies).toMutableList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = cookieStore[url.host] ?: emptyList()

        return cookies.filter { it.expiresAt >= System.currentTimeMillis() }
    }
}

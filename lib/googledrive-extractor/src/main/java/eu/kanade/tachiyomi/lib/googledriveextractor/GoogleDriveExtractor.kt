package eu.kanade.tachiyomi.lib.googledriveextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GoogleDriveExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }

    private val cookieList = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl())

    fun videosFromUrl(itemId: String, videoName: String = "Video"): List<Video> {
        val url = "https://drive.usercontent.google.com/download?id=$itemId"
        val docHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            add("Cookie", cookieList.toStr())
        }.build()

        val docResp = client.newCall(
            GET(url, docHeaders)
        ).execute()

        if (!docResp.peekBody(15).string().equals("<!DOCTYPE html>", true)) {
            return listOf(
                Video(url, videoName, url, docHeaders)
            )
        }

        val document = docResp.asJsoup()

        val itemSize = document.selectFirst("span.uc-name-size")
            ?.let { " ${it.ownText().trim()} " }
            ?: ""

        val videoUrl = url.toHttpUrl().newBuilder().apply {
            document.select("input[type=hidden]").forEach {
                setQueryParameter(it.attr("name"), it.attr("value"))
            }
        }.build().toString()

        return listOf(
            Video(videoUrl, videoName + itemSize, videoUrl, docHeaders)
        )
    }

    private fun List<Cookie>.toStr(): String {
        return this.joinToString("; ") { "${it.name}=${it.value}" }
    }
}

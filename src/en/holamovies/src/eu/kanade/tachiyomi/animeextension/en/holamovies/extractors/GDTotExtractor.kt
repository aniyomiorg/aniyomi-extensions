package eu.kanade.tachiyomi.animeextension.en.holamovies.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GDTotExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(serverUrl: String): List<Video> {
        val docHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", serverUrl.toHttpUrl().host)
            .build()

        val docResp = client.newCall(
            GET(serverUrl, headers = docHeaders),
        ).execute()
        val sessId = docResp.headers.firstOrNull {
            it.first.startsWith("set-cookie", true) && it.second.startsWith("PHPSESSID", true)
        }?.second?.substringBefore(";") ?: ""

        val ddlUrl = serverUrl.replace("/file/", "/ddl/")

        val ddlHeaders = docHeaders.newBuilder()
            .add("Cookie", sessId)
            .add("Referer", serverUrl)
            .build()

        val document = client.newCall(
            GET(ddlUrl, headers = ddlHeaders),
        ).execute().asJsoup()

        val btn = document.selectFirst("button[onclick~=drive.google.com]")?.attr("onclick") ?: return emptyList()

        return GoogleDriveExtractor(client, headers).videosFromUrl(btn.substringAfter("myDl('").substringBefore("'"), "GDToT")
    }
}

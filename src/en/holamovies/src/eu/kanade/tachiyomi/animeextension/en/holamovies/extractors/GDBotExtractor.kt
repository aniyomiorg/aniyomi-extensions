package eu.kanade.tachiyomi.animeextension.en.holamovies.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GDBotExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val botUrl = "https://gdbot.xyz"

    fun videosFromUrl(serverUrl: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val docHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", botUrl.toHttpUrl().host)
            .build()

        val fileId = serverUrl.substringAfter("/file/")
        val document = client.newCall(
            GET("$botUrl/file/$fileId", headers = docHeaders),
        ).execute().asJsoup()

        document.select("li.py-6 > a[href]").forEach {
            val url = it.attr("href")
            when {
                url.toHttpUrl().host.contains("gdflix") -> {
                    videoList.addAll(GDFlixExtractor(client, headers).videosFromUrl(url))
                }
//                url.toHttpUrl().host.contains("gdtot") -> {
//                    videoList.addAll(GDTotExtractor(client, headers).videosFromUrl(url))
//                }
            }
        }

        return videoList
    }
}

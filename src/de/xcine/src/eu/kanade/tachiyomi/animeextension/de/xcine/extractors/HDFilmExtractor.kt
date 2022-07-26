package eu.kanade.tachiyomi.animeextension.de.xcine.extractors

import eu.kanade.tachiyomi.animeextension.de.xcine.CookieInterceptor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class HDFilmExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val id = document.select("#movie_report_error_form input[name=movie_id]").attr("value")
        val link = if (document.select("#breadcrumbDiv a[title=\"Serien stream\"]").attr("href").contains("/serien1")) {
            document.select("link[rel=canonical]").attr("href")
        } else {
            val movielink = document.select("a.play-film").attr("href")
            // val cookies = cookieClient.newCall(GET(movielink)).execute().request.headers
            val moviehtml = client.newCall(GET(movielink)).execute().asJsoup()
            moviehtml.select("link[rel=canonical]").attr("href")
        }
        val loadid = if (document.select("#breadcrumbDiv a[title=\"Serien stream\"]").attr("href").contains("/serien1")) {
            document.select("a[href=$link]").attr("data-episode-id")
        } else {
            val moviehtml = client.newCall(GET(link)).execute().asJsoup()
            moviehtml.select("a#nextEpisodeToPlay").attr("data-episode-id")
        }
        val cookieClient = client.newBuilder().addInterceptor(CookieInterceptor()).build()
        val cookieheaders = cookieClient.newCall(GET(link)).execute().headers
        val headers = Headers.Builder()
            .addAll(cookieheaders)
            .add("origin", "https://xcine.me")
            .add("referer", link)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .build()
        val playlist = client.newCall(POST("https://xcine.me/movie/load-stream/$id/$loadid?server=1", headers = headers)).execute().asJsoup()
        playlist.toString().substringAfter("var vip_source = [{\"file\":\"").substringBefore("];").split("\"file\":\"").forEach {
            val quality = it.substringAfter("\"label\":\"").substringBefore("\"}")
            val videoUrl = it.substringAfter("\"file\":\"").substringBefore("\",\"type").replace("\\", "")
            if (client.newCall(GET(videoUrl)).execute().code == 204) {
                throw Exception("Einmal WebView öffnen und wieder schließen")
            } else {
                videoList.addAll((listOf(Video(videoUrl, quality, videoUrl))))
            }
        }
        return videoList
    }
}

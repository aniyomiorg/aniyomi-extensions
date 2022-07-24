package eu.kanade.tachiyomi.animeextension.de.xcine.extractors

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
            val movielink =
                document.select("a.play-film").attr("href")
            val moviehtml = client.newCall(GET(movielink)).execute().asJsoup()
            moviehtml.select("link[rel=canonical]").attr("href")
        }
        val loadid = if (document.select("#breadcrumbDiv a[title=\"Serien stream\"]").attr("href").contains("/serien1")) {
            document.select("a[href=$link]").attr("data-episode-id")
        } else {
            val moviehtml = client.newCall(GET(link)).execute().asJsoup()
            moviehtml.select("a#nextEpisodeToPlay").attr("data-episode-id")
        }
        val headers = Headers.Builder()
            .add("origin", "https://xcine.me")
            .add("referer", link)
            .add("user-agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .build()
        val playlist = client.newCall(POST("https://xcine.me/movie/load-stream/$id/$loadid?server=1", headers = headers)).execute().asJsoup()
        playlist.toString().substringAfter("var vip_source = [{\"file\":\"").substringBefore("];").split("\"file\":\"").forEach {
            val quality = it.substringAfter("\"label\":\"").substringBefore("\"}")
            val videoUrl = it.substringAfter("\"file\":\"").substringBefore("\",\"type").replace("\\", "")
            videoList.addAll((listOf(Video(videoUrl, quality, videoUrl, null))))
        }
        return videoList
    }
}

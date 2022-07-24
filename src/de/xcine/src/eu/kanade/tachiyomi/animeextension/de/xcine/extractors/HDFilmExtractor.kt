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
        val link = if (document.select("h1.title-film-detail-1").text().contains("Staffel")) {
            document.select("link[rel=canonical]").attr("href")
        } else {
            val movielink = document.select("a.play-film").attr("href")
            val headers = Headers.Builder()
                .add("origin", "https://xcine.me")
                .add("referer", movielink)
                .add("user-agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36")
                .add("cookie", "PHPSESSID=2fd5dbd220411ec5a3d0bc0d4213dd3b; SERVERID=s2; _ibota=_0xc60; _ga=GA1.2.1460439608.1658572957; _gid=GA1.2.584006335.1658572957; _pop=1; dom3ic8zudi28v8lr6fgphwffqoz0j6c=3a52b94d-a131-4af0-953c-154ce04acffc%3A1%3A1")
                .add("X-Requested-With", "xyz.jmir.tachiyomi.mi")
                .add("Content-Type", "application/x-www-form-urlencoded")
                .build()
            val moviehtml = client.newCall(GET(movielink, headers = headers)).execute().asJsoup()
            moviehtml.select("link[rel=canonical]").attr("href")
        }
        val loadid = if (document.select("h1.title-film-detail-1").text().contains("Staffel")) {
            document.select("a[href=$link]").attr("data-episode-id")
        } else {
            val headers = Headers.Builder()
                .add("origin", "https://xcine.me")
                .add("referer", link)
                .add("user-agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36")
                .add("cookie", "PHPSESSID=dfe9b1d10226c722092f7c3c42df9163; SERVERID=s2; _mFEcx=_0xc56; _ga=GA1.2.826139063.1658618592; _gid=GA1.2.162365688.1658618592; _gat_gtag_UA_144665518_1=1; __gads=ID=4fe53672dd9aeab8-22d4864165d40078:T=1658618593:RT=1658618593:S=ALNI_MYrmBvhl86smHeaNwW8I5UMS2ZOlA; _pop=1; dom3ic8zudi28v8lr6fgphwffqoz0j6c=3f6f53a9-7930-4d7a-87b1-c67125d128e0%3A2%3A1; m5a4xojbcp2nx3gptmm633qal3gzmadn=hopefullyapricot.com")
                .add("X-Requested-With", "xyz.jmir.tachiyomi.mi")
                .add("Content-Type", "application/x-www-form-urlencoded")
                .build()
            val moviehtml = client.newCall(GET(link, headers = headers)).execute().asJsoup()
            moviehtml.select("a#nextEpisodeToPlay").attr("data-episode-id")
        }
        val headers = Headers.Builder()
            .add("origin", "https://xcine.me")
            .add("referer", link)
            .add("user-agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36")
            .add("cookie", "PHPSESSID=dfe9b1d10226c722092f7c3c42df9163; SERVERID=s2; _mFEcx=_0xc56; _ga=GA1.2.826139063.1658618592; _gid=GA1.2.162365688.1658618592; _gat_gtag_UA_144665518_1=1; __gads=ID=4fe53672dd9aeab8-22d4864165d40078:T=1658618593:RT=1658618593:S=ALNI_MYrmBvhl86smHeaNwW8I5UMS2ZOlA; _pop=1; dom3ic8zudi28v8lr6fgphwffqoz0j6c=3f6f53a9-7930-4d7a-87b1-c67125d128e0%3A2%3A1; m5a4xojbcp2nx3gptmm633qal3gzmadn=hopefullyapricot.com")
            .add("X-Requested-With", "xyz.jmir.tachiyomi.mi")
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

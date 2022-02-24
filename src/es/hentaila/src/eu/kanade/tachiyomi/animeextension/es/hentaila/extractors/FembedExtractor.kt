package eu.kanade.tachiyomi.animeextension.es.hentaila.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup

class FembedExtractor {
    fun videosFromUrl(url: String): List<Video> {
        val videoApi = url.replace("/v/", "/api/source/")
        val json = JSONObject(Jsoup.connect(videoApi).ignoreContentType(true).method(Connection.Method.POST).execute().body())
        val videoList = mutableListOf<Video>()
        if (json.getBoolean("success")) {
            val videoList = mutableListOf<Video>()
            val jsonArray = json.getJSONArray("data")
            for (i in 0 until jsonArray.length()) {
                val `object` = jsonArray.getJSONObject(i)
                val videoUrl = `object`.getString("file")
                val quality = "Fembed:" + `object`.getString("label")
                videoList.add(Video(videoUrl, quality, videoUrl, null))
            }
            return videoList
        } else {
            val videoUrl = "not used"
            val quality = "Video taken down for dmca"
            videoList.add(Video(videoUrl, quality, videoUrl, null))
        }
        return videoList
    }
}

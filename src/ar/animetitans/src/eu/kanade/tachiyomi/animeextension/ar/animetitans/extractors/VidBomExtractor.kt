package eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VidBomExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")
        val data = script.data().substringAfter("sources: [").substringBefore("],")
        val sources = data.split("file:\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringBefore("\"")
            val quality = "Vidbom:" + source.substringAfter("label:\"").substringBefore("\"") // .substringAfter("format: '")
            val video = Video(src, quality, src)
            videoList.add(video)
        }
        return videoList
        /*Log.i("looool", "$js")
        val json = JSONObject(js)
        Log.i("looool", "$json")
        val videoList = mutableListOf<Video>()
        val jsonArray = json.getJSONArray("sources")
        for (i in 0 until jsonArray.length()) {
            val `object` = jsonArray.getJSONObject(i)
            val videoUrl = `object`.getString("file")
            Log.i("looool", videoUrl)
            val quality = "Vidbom:" + `object`.getString("label")
            videoList.add(Video(videoUrl, quality, videoUrl))
        }
        return videoList*/
        /*if (jas.contains("sources")) {
            val js = script.data()
            val json = JSONObject(js)
            val videoList = mutableListOf<Video>()
            val jsonArray = json.getJSONArray("sources")
            for (i in 0 until jsonArray.length()) {
                val `object` = jsonArray.getJSONObject(i)
                val videoUrl = `object`.getString("file")
                Log.i("lol", videoUrl)
                val quality = "Vidbom:" + `object`.getString("label")
                videoList.add(Video(videoUrl, quality, videoUrl))
            }
            return videoList
        } else {
            val videoList = mutableListOf<Video>()
            videoList.add(Video(url, "no 2video", null))
            return videoList
        }*/
    }
}

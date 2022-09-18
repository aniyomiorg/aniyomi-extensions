package eu.kanade.tachiyomi.animeextension.pt.animesonlinex.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class GenericExtractor(
    private val client: OkHttpClient,
    private val headers: Headers
) {

    fun getVideoList(url: String, qualityStr: String): List<Video> {
        val response = client.newCall(GET(url, headers)).execute()
        val body = response.body!!.string()
        val item = if ("/firestream/" in url) "play_url" else "file"
        val REGEX_URL = Regex("${item}\":\"(.*?)\"")
        val videoUrl = REGEX_URL.find(body)!!.groupValues.get(1)
        return listOf(Video(videoUrl, qualityStr, videoUrl, headers))
    }
}

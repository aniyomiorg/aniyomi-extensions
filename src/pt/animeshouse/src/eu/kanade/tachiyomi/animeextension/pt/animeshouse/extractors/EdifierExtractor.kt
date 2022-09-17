package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.OkHttpClient

class EdifierExtractor(
    private val client: OkHttpClient,
    private val headers: Headers
) {

    private val REGEX_EDIFIER = Regex(""""file":"(.*?)","label":"(\S+?)"""")
    private val PLAYER_NAME = "EDIFIER"

    fun getVideoList(url: String): List<Video> {
        val apiUrl = url.replace("/v/", "/api/source/")
        val req = client.newCall(POST(apiUrl)).execute()
        val body = req.body?.string().orEmpty()
        return REGEX_EDIFIER.findAll(body).map {
            val videoUrl = it.groupValues.get(1).replace("\\", "")
            val quality = "$PLAYER_NAME: " + it.groupValues.get(2)
            Video(videoUrl, quality, videoUrl, headers)
        }.toList()
    }
}

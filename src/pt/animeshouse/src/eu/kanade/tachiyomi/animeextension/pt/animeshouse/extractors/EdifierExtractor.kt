package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.OkHttpClient

class EdifierExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val regexEdifier = Regex(""""file":"(.*?)","label":"(\S+?)"""")
    private val playerName = "EDIFIER"

    fun getVideoList(url: String): List<Video> {
        val apiUrl = url.replace("/v/", "/api/source/")
        val req = client.newCall(POST(apiUrl)).execute()
        val body = req.body.string()
        return regexEdifier.findAll(body).map {
            val videoUrl = it.groupValues.get(1).replace("\\", "")
            val quality = "$playerName: " + it.groupValues.get(2)
            Video(videoUrl, quality, videoUrl, headers)
        }.toList()
    }
}

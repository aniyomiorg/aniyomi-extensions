package eu.kanade.tachiyomi.animeextension.pt.goyabu.extractors

import eu.kanade.tachiyomi.animeextension.pt.goyabu.GYConstants
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class PlayerOneExtractor {

    private val PREFIX = "Player 1"
    private val PLAYER_REGEX = Regex("""(?s)label: "(\w+)".*?file: "(.*?)"""")

    fun videoListFromHtml(
        html: String,
        headers: Headers? = null
    ): List<Video> {
        return PLAYER_REGEX.findAll(html).map { it ->
            val quality = "$PREFIX (${it.groupValues[1]})"
            val videoUrl = it.groupValues[2]
            Video(videoUrl, quality, videoUrl, null, headers)
        }.toList()
    }

    fun videoListFromKanraUrl(url: String, client: OkHttpClient): List<Video> {
        val headers = Headers.headersOf(
            "User-Agent", GYConstants.USER_AGENT
        )
        val res = client.newCall(GET(url, headers)).execute()
        val html = res.body?.string().orEmpty()
        return videoListFromHtml(html, headers)
    }
}

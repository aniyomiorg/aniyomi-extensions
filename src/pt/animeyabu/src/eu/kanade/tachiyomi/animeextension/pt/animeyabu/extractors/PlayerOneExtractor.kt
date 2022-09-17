package eu.kanade.tachiyomi.animeextension.pt.animeyabu.extractors

import eu.kanade.tachiyomi.animeextension.pt.animeyabu.AYConstants
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class PlayerOneExtractor(private val client: OkHttpClient? = null) {

    private val KANRA_REGEX = Regex("""(?s)label: "(\w+)".*?file: "(.*?)"""")
    private val PREFIX = "Player 1"

    fun videoListFromHtml(html: String, regex: Regex = AYConstants.PLAYER_REGEX, headers: Headers? = null): List<Video> {
        return regex.findAll(html).map { it ->
            val quality = "$PREFIX (${it.groupValues[1]})"
            val videoUrl = it.groupValues[2]
            Video(videoUrl, quality, videoUrl, headers = headers)
        }.toList()
    }

    fun videoListFromKanraUrl(url: String): List<Video> {
        val headers = Headers.headersOf(
            "User-Agent", AYConstants.USER_AGENT,
        )
        val res = client!!.newCall(GET(url, headers)).execute()
        val html = res.body?.string().orEmpty()
        return videoListFromHtml(html, KANRA_REGEX, headers)
    }
}

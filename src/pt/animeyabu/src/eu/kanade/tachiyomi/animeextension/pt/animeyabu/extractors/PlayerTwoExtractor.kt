package eu.kanade.tachiyomi.animeextension.pt.animeyabu.extractors

import eu.kanade.tachiyomi.animeextension.pt.animeyabu.AYConstants
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class PlayerTwoExtractor(private val client: OkHttpClient) {

    private val PREFIX = "Player 2"

    fun videoFromPlayerUrl(url: String): Video? {
        val headers = Headers.headersOf("User-Agent", AYConstants.USER_AGENT)
        val res = client.newCall(GET(url, headers)).execute()
        val html = res.body?.string().orEmpty()
        val match = AYConstants.PLAYER_REGEX.find(html)
        if (match == null) {
            return match
        }
        val quality = "$PREFIX (${match.groupValues[1]})"
        val videoUrl = match.groupValues[2]
        return Video(videoUrl, quality, videoUrl)
    }
}

package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers

class MpFourDooExtractor(private val headers: Headers) {

    private val REGEX_MPDOO = Regex("file\":\"(.*?)\"")
    private val PLAYER_NAME = "Mp4Doo"

    fun getVideoList(js: String): List<Video> {
        val videoUrl = REGEX_MPDOO.find(js)!!.groupValues
            .get(1)
            .replace("fy..", "fy.v.")
        return listOf(Video(videoUrl, PLAYER_NAME, videoUrl, headers))
    }
}

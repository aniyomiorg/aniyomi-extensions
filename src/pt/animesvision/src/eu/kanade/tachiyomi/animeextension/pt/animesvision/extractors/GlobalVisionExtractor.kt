package eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors

import eu.kanade.tachiyomi.animesource.model.Video
class GlobalVisionExtractor {

    private val REGEX_URL = Regex(""""file":"(\S+?)",.*?"label":"(.*?)"""")
    private val PREFIX = "GlobalVision"

    fun videoListFromHtml(html: String): List<Video> {
        return REGEX_URL.findAll(html).map {
            val videoUrl = it.groupValues[1].replace("\\", "")
            val qualityName = it.groupValues[2]
            Video(videoUrl, "$PREFIX $qualityName", videoUrl)
        }.toList()
    }
}

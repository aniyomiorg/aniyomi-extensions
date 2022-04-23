package eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors

import eu.kanade.tachiyomi.animesource.model.Video

class VisionFreeExtractor {

    private val REGEX_VISION_PLAYER = Regex(""""file":"(\S+?)",.*?"label":"(.*?)"""")
    private val TAG = "VisionFreeExtractor"

    fun videoListFromHtml(html: String): List<Video> {
        return REGEX_VISION_PLAYER.findAll(html).map {
            val videoUrl = it.groupValues[1].replace("\\", "")
            val quality = it.groupValues[2]
            Video(videoUrl, quality, videoUrl, null)
        }.toList()
    }
}

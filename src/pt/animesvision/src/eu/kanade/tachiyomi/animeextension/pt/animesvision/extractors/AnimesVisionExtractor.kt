package eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors

import eu.kanade.tachiyomi.animesource.model.Video

object AnimesVisionExtractor {
    private val REGEX_URL = Regex(""""file":"(\S+?)",.*?"label":"(.*?)"""")

    fun videoListFromScript(encodedScript: String): List<Video> {
        val decodedScript = JsDecoder.decodeScript(encodedScript)
        return REGEX_URL.findAll(decodedScript).map {
            val videoUrl = it.groupValues[1].replace("\\", "")
            val qualityName = it.groupValues[2]
            Video(videoUrl, "PlayerVision $qualityName", videoUrl)
        }.toList()
    }
}

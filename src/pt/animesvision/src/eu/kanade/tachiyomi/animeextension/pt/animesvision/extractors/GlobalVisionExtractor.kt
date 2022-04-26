package eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors

import eu.kanade.tachiyomi.animesource.model.Video
class GlobalVisionExtractor {

    private val REGEX_URL = Regex("""file: "(\S+?)",""")
    private val PREFIX = "GlobalVision"

    fun videoListFromHtml(html: String, players: String): List<Video> {
        val matches = REGEX_URL.find(html)
        if (matches == null)
            return emptyList<Video>()
        val url = matches.groupValues[1]
        val qualities = mapOf("SD" to "480p", "HD" to "720p", "FULLHD" to "1080p")
        return qualities.mapNotNull { (qualityName, qualityStr) ->
            if (qualityName in players) {
                val videoUrl = if ("480p" in url) url else url.replace("480p", qualityStr)
                Video(videoUrl, "$PREFIX $qualityName", videoUrl, null)
            } else { null }
        }
    }
}

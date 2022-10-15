package eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors

import eu.kanade.tachiyomi.animesource.model.Video

class VisionExtractor {

    fun videoFromHtml(html: String): Video? {
        val videoUrl = html.substringAfter("url: \"").substringBefore("\"")
        if (videoUrl.startsWith("<div")) return null
        val quality = videoUrl.substringBeforeLast("/").substringAfterLast("/")
        return Video(videoUrl, "Vision $quality", videoUrl)
    }
}

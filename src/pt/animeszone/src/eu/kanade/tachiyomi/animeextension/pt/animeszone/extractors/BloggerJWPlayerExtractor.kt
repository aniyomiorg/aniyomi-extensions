package eu.kanade.tachiyomi.animeextension.pt.animeszone.extractors

import eu.kanade.tachiyomi.animesource.model.Video

object BloggerJWPlayerExtractor {
    fun videosFromScript(script: String): List<Video> {
        val sources = script.substringAfter("sources: [").substringBefore("],")

        return sources.split("{").drop(1).mapNotNull {
            val label = it.substringAfter("label")
                .substringAfter(':')
                .substringAfter('"')
                .substringBefore('"')

            val videoUrl = it.substringAfter("file")
                .substringAfter(':')
                .substringAfter('"')
                .substringBefore('"')
                .replace("\\", "")
            if (videoUrl.isEmpty()) {
                null
            } else {
                Video(videoUrl, "BloggerJWPlayer - $label", videoUrl)
            }
        }
    }
}

package eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video

object BloggerJWPlayerExtractor {
    fun videosFromScript(script: String): List<Video> {
        val sources = script.substringAfter("sources: [").substringBefore("],")

        return sources.split("{").drop(1).map {
            val label = it.substringAfter("label").substringAfter(":\"").substringBefore('"')
            val videoUrl = it.substringAfter("file")
                .substringAfter(":\"")
                .substringBefore('"')
                .replace("\\", "")
            Video(videoUrl, "BloggerJWPlayer - $label", videoUrl)
        }
    }
}

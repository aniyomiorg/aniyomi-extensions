package eu.kanade.tachiyomi.animeextension.pt.animeszone.extractors

import eu.kanade.tachiyomi.animesource.model.Video

object PlaylistExtractor {
    fun videosFromScript(script: String): List<Video> {
        val sources = script.substringAfter("sources: [").substringBefore("],")

        return sources.split("file:\"").drop(1).mapNotNull { source ->
            val url = source.substringBefore("\"").ifEmpty { return@mapNotNull null }
            val label = source.substringAfter("label:\"").substringBefore("\"")
                .replace("FHD", "1080p")
                .replace("HD", "720p")
                .replace("SD", "480p")
            Video(url, "Playlist - $label", url)
        }
    }
}

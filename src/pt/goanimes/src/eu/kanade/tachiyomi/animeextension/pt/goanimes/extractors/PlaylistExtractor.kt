package eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video

object PlaylistExtractor {
    fun videosFromScript(script: String, prefix: String = "Playlist"): List<Video> {
        val sources = script.substringAfter("sources: [").substringBefore("],")

        return sources.split("{").drop(1).mapNotNull { source ->
            val url = source.substringAfter("file:")
                .substringAfter('"', "")
                .substringBefore('"', "")
                .takeIf(String::isNotEmpty)
                ?: source.substringAfter("file:")
                    .substringAfter("'", "")
                    .substringBefore("'", "")
                    .takeIf(String::isNotEmpty)

            if (url.isNullOrBlank()) {
                return@mapNotNull null
            }

            val label = source.substringAfter("label:").substringAfter('"').substringBefore('"')
                .replace("FHD", "1080p")
                .replace("HD", "720p")
                .replace("SD", "480p")
            Video(url, "$prefix - $label", url)
        }
    }
}

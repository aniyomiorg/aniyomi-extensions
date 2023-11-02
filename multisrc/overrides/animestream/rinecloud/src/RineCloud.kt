package eu.kanade.tachiyomi.animeextension.pt.rinecloud

import eu.kanade.tachiyomi.animeextension.pt.rinecloud.extractors.RineCloudExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class RineCloud : AnimeStream(
    "pt-BR",
    "RineCloud",
    "https://rine.cloud",
) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    override val prefQualityEntries = prefQualityValues

    private val rinecloudExtractor by lazy { RineCloudExtractor(client, headers) }
    override fun getVideoList(url: String, name: String): List<Video> {
        return when {
            "rine.cloud" in url -> rinecloudExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }
}

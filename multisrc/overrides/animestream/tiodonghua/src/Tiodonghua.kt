package eu.kanade.tachiyomi.animeextension.es.tiodonghua

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class Tiodonghua : AnimeStream(
    "es",
    "Tiodonghua.com",
    "https://anime.tiodonghua.com",
) {

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val youruploadExtractor by lazy { YourUploadExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when (name) {
            "Okru" -> okruExtractor.videosFromUrl(url)
            "Voe" -> voeExtractor.videosFromUrl(url)
            "YourUpload" -> youruploadExtractor.videoFromUrl(url, headers)
            "MixDrop" -> mixdropExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    override val fetchFilters: Boolean
        get() = false
}

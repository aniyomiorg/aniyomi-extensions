package eu.kanade.tachiyomi.animeextension.es.animeytes

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class AnimeYTES : AnimeStream(
    "es",
    "AnimeYT.es",
    "https://animeyt.es",
) {
    override val animeListUrl = "$baseUrl/tv"

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when (name) {
            "OK" -> okruExtractor.videosFromUrl(url)
            "Stream" -> streamtapeExtractor.videosFromUrl(url)
            "Send" -> sendvidExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }
}

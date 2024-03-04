package eu.kanade.tachiyomi.animeextension.en.zoro

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme

class HiAnime : ZoroTheme(
    "en",
    "HiAnime",
    "https://hianime.to",
) {
    override val id = 6706411382606718900L

    override val ajaxRoute = "/v2"

    override val hosterNames: List<String> = listOf(
        "Vidstreaming",
        "MegaCloud",
        "StreamTape",
    )

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(server.link, "Streamtape - ${server.type}")
                    ?.let(::listOf)
                    ?: emptyList()
            }
            "Vidstreaming", "MegaCloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }
}

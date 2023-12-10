package eu.kanade.tachiyomi.animeextension.sr.animebalkan

import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class AnimeBalkan : AnimeStream(
    "sr",
    "AnimeBalkan",
    "https://animebalkan.org",
) {
    override val animeListUrl = "$baseUrl/animesaprevodom"
}

package eu.kanade.tachiyomi.animeextension.id.animeindo

import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class AnimeIndo : AnimeStream(
    "id",
    "AnimeIndo",
    "https://animeindo.quest",
) {
    override val animeListUrl = "$baseUrl/pages/animelist"

    // ============================== Filters ===============================
    override val fetchFilters = false
}

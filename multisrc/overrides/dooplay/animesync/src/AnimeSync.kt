package eu.kanade.tachiyomi.animeextension.pt.animesync

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET

class AnimeSync : DooPlay(
    "pt-BR",
    "AnimeSync",
    "https://animesync.org",
) {
    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/generos/")
    override fun genresListSelector() = "ul.generos li > a"
}

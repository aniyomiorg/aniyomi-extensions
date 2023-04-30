package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET

class AnimePlayer : DooPlay(
    "pt-BR",
    "AnimePlayer",
    "https://animeplayer.com.br",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#featured-titles article div.poster"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/")

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesNextPageSelector() = "a > i#nextpagination"

    // ============================== Filters ===============================
    override fun genresListSelector() = "ul.genres a"
}

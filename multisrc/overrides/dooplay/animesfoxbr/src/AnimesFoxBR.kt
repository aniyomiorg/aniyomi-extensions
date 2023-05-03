package eu.kanade.tachiyomi.animeextension.pt.animesfoxbr

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET

class AnimesFoxBR : DooPlay(
    "pt-BR",
    "AnimesFox BR",
    "https://animesfoxbr.com",
) {
    // ============================== Popular ===============================
    // The site doesn't have a true popular anime tab,
    // so we use the latest added anime page instead.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/page/$page")

    override fun popularAnimeSelector() = "div.clw div.b_flex > div > a"

    override fun popularAnimeNextPageSelector() = "div.pagination i#nextpagination"

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
}

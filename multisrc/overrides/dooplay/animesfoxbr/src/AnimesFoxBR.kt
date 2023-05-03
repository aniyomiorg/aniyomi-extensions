package eu.kanade.tachiyomi.animeextension.pt.animesfoxbr

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import org.jsoup.nodes.Document

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

    // =============================== Search ===============================
    override fun searchAnimeNextPageSelector() = "div.pagination > *:last-child:not(.current)"

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/categorias")

    override fun genresListSelector() = "div.box_category > a"

    override fun genresListParse(document: Document) =
        super.genresListParse(document).map {
            Pair(it.first.substringAfter(" "), it.second)
        }.toTypedArray()

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
}

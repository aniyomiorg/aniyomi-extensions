package eu.kanade.tachiyomi.animeextension.pt.animesync

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import org.jsoup.nodes.Element

class AnimeSync : DooPlay(
    "pt-BR",
    "AnimeSync",
    "https://animesync.org",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.imdbRating > article > a"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes")

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div.items > article.item"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/generos/")
    override fun genresListSelector() = "ul.generos li > a"
}

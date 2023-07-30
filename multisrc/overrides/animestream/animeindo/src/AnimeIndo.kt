package eu.kanade.tachiyomi.animeextension.id.animeindo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class AnimeIndo : AnimeStream(
    "id",
    "AnimeIndo",
    "https://animeindo.quest",
) {
    override val animeListUrl = "$baseUrl/pages/animelist"

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/page/$page/?order=popular")

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/page/$page/?order=update")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$animeListUrl/page/$page/?title=$query")

    override fun searchAnimeSelector() = "div.animepost > div > a"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.title")!!.text()
        thumbnail_url = element.selectFirst("img")!!.getImageUrl()
    }

    override fun searchAnimeNextPageSelector() = "div.pagination a:contains(i#nextpagination)"

    // ============================== Filters ===============================
    override val fetchFilters = false
}

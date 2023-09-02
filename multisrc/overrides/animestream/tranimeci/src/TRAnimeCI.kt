package eu.kanade.tachiyomi.animeextension.tr.tranimeci

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET

class TRAnimeCI : AnimeStream(
    "tr",
    "TRAnimeCI",
    "https://tranimeci.com",
) {
    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(ShittyProtectionInterceptor(network.client))
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.releases:contains(Populer) + div.listupd a.tip"

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/index?page=$page")

    override fun latestUpdatesSelector() = "div.releases:contains(Son Güncellenenler) ~ div.listupd a.tip"

    override fun latestUpdatesNextPageSelector() = "div.hpage > a:last-child[href]"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/search?name=$query")

    override fun searchAnimeSelector() = "div.advancedsearch a.tip"

    override fun searchAnimeNextPageSelector() = null

    // ============================== Filters ===============================
    override val fetchFilters = false
}

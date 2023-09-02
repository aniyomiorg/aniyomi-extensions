package eu.kanade.tachiyomi.animeextension.tr.tranimeci

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

    // ============================== Filters ===============================
    override val fetchFilters = false
}

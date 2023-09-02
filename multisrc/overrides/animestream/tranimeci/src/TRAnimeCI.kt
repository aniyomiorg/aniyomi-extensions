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

    // ============================== Filters ===============================
    override val fetchFilters = false
}

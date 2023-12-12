package eu.kanade.tachiyomi.animeextension.fr.voircartoon

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET

class VoirCartoon : DooPlay(
    "fr",
    "VoirCartoon",
    "https://voircartoon.com",
) {
    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendance/page/$page/", headers)

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = "div.pagination a.arrow_pag > i#nextpagination"

    // =============================== Latest ===============================
    override val supportsLatest = false

    // =============================== Search ===============================
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
}

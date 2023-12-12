package eu.kanade.tachiyomi.animeextension.fr.voircartoon

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import org.jsoup.nodes.Document

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

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) =
        super.animeDetailsParse(document).apply {
            val statusText = document.selectFirst("div.mvic-info p:contains(Status:) > a[rel]")
                ?.text()
                .orEmpty()
            status = parseStatus(statusText)
        }

    private fun parseStatus(status: String): Int {
        return when (status) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }
}

package eu.kanade.tachiyomi.animeextension.fr.voircartoon

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { it.asJsoup() }
        val episodeList = doc.select(episodeListSelector())
        return if (episodeList.size < 1) {
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                episode_number = 1F
                name = episodeMovieText
            }.let(::listOf)
        } else {
            episodeList.map(::episodeFromElement).reversed()
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val epNum = element.selectFirst("div.numerando")!!.text()
            .trim()
            .let(episodeNumberRegex::find)
            ?.groupValues
            ?.last() ?: "0"
        val href = element.selectFirst("a[href]")!!
        val episodeName = href.ownText()
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "Saison" + episodeName.substringAfterLast("Saison")
        setUrlWithoutDomain(href.attr("href"))
    }
}

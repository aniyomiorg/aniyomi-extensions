package eu.kanade.tachiyomi.animeextension.en.pactedanime

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Element

class PactedAnime : DooPlay(
    "en",
    "pactedanime",
    "https://pactedanime.com",
) {

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = latestUpdatesSelector()

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page/")

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "#nextpagination"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun getSeasonEpisodes(season: Element) = super.getSeasonEpisodes(season).reversed()

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        val episode = super.episodeFromElement(element, seasonName)

        element.selectFirst("p:contains(Filler)")?.let {
            episode.scanlator = "Filler Episode"
        }

        return episode
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }

        return document.select("div#playcontainer > div > div").mapNotNull {
            if (!it.getElementsByTag("video").isEmpty()) {
                runCatching {
                    val source = it.selectFirst("source")!!
                    val link = source.attr("src")
                    val quality = source.attr("label")
                    Video(link, quality, link)
                }.getOrNull()
            } else {
                null
            }
        }
    }

    // ============================== Settings ==============================
    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    override val prefQualityEntries = prefQualityValues

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-bars"
}

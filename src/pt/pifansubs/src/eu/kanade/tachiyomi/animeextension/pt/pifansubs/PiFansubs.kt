package eu.kanade.tachiyomi.animeextension.pt.pifansubs

import eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors.BlembedExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PiFansubs : DooPlay(
    "pt-BR",
    "Pi Fansubs",
    "https://pifansubs.club",
) {

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override val prefQualityValues = arrayOf("360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("div.source-box:not(#source-player-trailer) iframe")
        return players.map(::getPlayerUrl).flatMap(::getPlayerVideos)
    }

    private fun getPlayerUrl(player: Element): String {
        return player.attr("data-src").ifEmpty { player.attr("src") }.let {
            when {
                !it.startsWith("http") -> "https:" + it
                else -> it
            }
        }
    }

    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client) }
    private val blembedExtractor by lazy { BlembedExtractor(client, headers) }

    private fun getPlayerVideos(url: String): List<Video> {
        return when {
            "https://vidhide" in url -> streamHideVidExtractor.videosFromUrl(url)
            "https://blembed" in url -> blembedExtractor.videosFromUrl(url)
            else -> emptyList<Video>()
        }
    }

    // =========================== Anime Details ============================
    override fun Document.getDescription(): String {
        return select("$additionalInfoSelector p")
            .eachText()
            .joinToString("\n\n") + "\n"
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episodios/page/$page", headers)
}

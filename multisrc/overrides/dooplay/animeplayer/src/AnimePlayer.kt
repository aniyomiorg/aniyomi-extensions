package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import eu.kanade.tachiyomi.animeextension.pt.animeplayer.extractors.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

class AnimePlayer : DooPlay(
    "pt-BR",
    "AnimePlayer",
    "https://animeplayer.com.br",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#featured-titles article div.poster"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/")

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesNextPageSelector() = "a > i#nextpagination"

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("360p", "720p")
    override val prefQualityEntries = prefQualityValues

    override fun videoListParse(response: Response): List<Video> {
        val player = response.asJsoup().selectFirst("div.playex iframe") ?: return emptyList()
        return BloggerExtractor(client).videosFromUrl(player.attr("src"), headers)
    }

    // ============================== Filters ===============================
    override fun genresListSelector() = "ul.genres a"
}

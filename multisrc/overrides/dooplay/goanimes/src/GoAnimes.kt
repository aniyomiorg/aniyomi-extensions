package eu.kanade.tachiyomi.animeextension.pt.goanimes

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import org.jsoup.nodes.Element

class GoAnimes : DooPlay(
    "pt-BR",
    "GoAnimes",
    "https://goanimes.net",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#featured-titles article.item.tvshows > div.poster"

    // ============================== Episodes ==============================
    override val seasonListSelector = "div#seasons > *"

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        // All episodes are listed under a single page
        season.selectFirst(episodeListSelector())?.let {
            return super.getSeasonEpisodes(season)
        }

        // Episodes are listed at another page
        val url = season.attr("href")
        return client.newCall(GET(url))
            .execute()
            .asJsoup()
            .let { super.getSeasonEpisodes(it) }
    }

    // =============================== Latest ===============================
    override val latestUpdatesPath = "lancamentos"
}

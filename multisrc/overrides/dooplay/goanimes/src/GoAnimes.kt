package eu.kanade.tachiyomi.animeextension.pt.goanimes

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay

class GoAnimes : DooPlay(
    "pt-BR",
    "GoAnimes",
    "https://goanimes.net",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#featured-titles article.item.tvshows > div.poster"

    // =============================== Latest ===============================
    override val latestUpdatesPath = "lancamentos"
}

package eu.kanade.tachiyomi.animeextension.hi.animesaga

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay

class AnimeSAGA : DooPlay(
    "hi",
    "AnimeSAGA",
    "https://www.animesaga.in",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.top-imdb-list > div.top-imdb-item"
}

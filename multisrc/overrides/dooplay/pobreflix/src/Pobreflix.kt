package eu.kanade.tachiyomi.animeextension.pt.pobreflix

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay

class Pobreflix : DooPlay(
    "pt-BR",
    "Pobreflix",
    "https://pobreflix.biz",
) {

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.featured div.poster"
}

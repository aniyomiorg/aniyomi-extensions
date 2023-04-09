package eu.kanade.tachiyomi.animeextension.pt.goanimes

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay

class GoAnimes : DooPlay(
    "pt-BR",
    "GoAnimes",
    "https://goanimes.net",
) {

    // =============================== Latest ===============================
    override val latestUpdatesPath = "lancamentos"
}

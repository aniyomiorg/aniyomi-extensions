package eu.kanade.tachiyomi.animeextension.pt.animesgratis

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET

class AnimesGratis : DooPlay(
    "pt-BR",
    "Animes Grátis",
    "https://animesgratis.org",
) {

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.imdbRating > article > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/")
}

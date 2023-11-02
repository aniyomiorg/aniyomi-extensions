package eu.kanade.tachiyomi.animeextension.es.cuevana

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class CuevanaFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        CuevanaCh("Cuevana3Ch", "https://ww1.cuevana3.ch"),
        CuevanaEu("Cuevana3Eu", "https://www.cuevana3.eu"),
    )
}

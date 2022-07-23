package eu.kanade.tachiyomi.animeextension.all.onepace

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class OnepaceFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        OnepaceEspa(),
        OnepaceFr(),
        OnepaceEn()
    )
}

class OnepaceEspa : Onepace("es", "OnePaceESP")
class OnepaceFr : Onepace("fr", "OnePaceFR")
class OnepaceEn : Onepace("en", "OnePaceEN")

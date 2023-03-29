package eu.kanade.tachiyomi.animeextension.ru.animevost

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class Animevost : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf<AnimeSource>(
        AnimevostSource("Animevost", "https://animevost.org"),
        AnimevostSource("Animevost Mirror", "https://v2.vost.pw"),
    )
}

package eu.kanade.tachiyomi.animeextension.uk.ufdub

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class UFDub : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf<AnimeSource>(
        UFDubSource("UFDub", "https://ufdub.com/anime"),
    )
}

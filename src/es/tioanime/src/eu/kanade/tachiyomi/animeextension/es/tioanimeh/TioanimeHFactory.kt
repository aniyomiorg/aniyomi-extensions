package eu.kanade.tachiyomi.animeextension.es.tioanimeh

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class TioanimeHFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        tioanime(),
        tiohentai()
    )
}

class tioanime : TioanimeH("TioAnime", "https://tioanime.com") {
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}

class tiohentai : TioanimeH("TioHentai", "https://tiohentai.com")

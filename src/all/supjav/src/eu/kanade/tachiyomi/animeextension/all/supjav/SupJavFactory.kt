package eu.kanade.tachiyomi.animeextension.all.supjav

import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class SupJavFactory : AnimeSourceFactory {
    override fun createSources() = listOf(
        SupJav("en"),
        SupJav("ja"),
        SupJav("zh"),
    )
}

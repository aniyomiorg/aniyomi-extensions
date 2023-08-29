package eu.kanade.tachiyomi.animeextension.all.jellyfin

import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class JellyfinFactory : AnimeSourceFactory {
    override fun createSources() = listOf(
        Jellyfin(""),
        Jellyfin("2"),
        Jellyfin("3"),
    )
}

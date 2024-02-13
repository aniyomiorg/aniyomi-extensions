package eu.kanade.tachiyomi.animeextension.all.jellyfin

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class JellyfinFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> {
        val firstJelly = Jellyfin("1")
        val extraCount = firstJelly.preferences.getString(Jellyfin.EXTRA_SOURCES_COUNT_KEY, Jellyfin.EXTRA_SOURCES_COUNT_DEFAULT)!!.toInt()

        return buildList(extraCount) {
            add(firstJelly)
            for (i in 2..extraCount) {
                add(Jellyfin("$i"))
            }
        }
    }
}

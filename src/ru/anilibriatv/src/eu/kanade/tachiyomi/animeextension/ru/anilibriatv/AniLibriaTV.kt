package eu.kanade.tachiyomi.animeextension.ru.anilibriatv

import eu.kanade.tachiyomi.animeextension.ru.anilibriatv.api.AniLibriaTVApiV3
import eu.kanade.tachiyomi.animeextension.ru.anilibriatv.api.UAKino
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class AniLibriaTV : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf<AnimeSource>(
        AniLibriaTVApiV3("Anilibria TV", "https://api.anilibria.tv/v3"),
    )
}

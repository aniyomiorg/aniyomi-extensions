package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class AnimeWorldIndiaFactory : AnimeSourceFactory {

    override fun createSources() = listOf(
        AnimeWorldIndia("all", ""),
        AnimeWorldIndia("bn", "bengali"),
        AnimeWorldIndia("en", "english"),
        AnimeWorldIndia("hi", "hindi"),
        AnimeWorldIndia("ja", "japanese"),
        AnimeWorldIndia("ml", "malayalam"),
        AnimeWorldIndia("mr", "marathi"),
        AnimeWorldIndia("ta", "tamil"),
        AnimeWorldIndia("te", "telugu"),
    )
}

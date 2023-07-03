package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class PelisplushdFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        Pelisplushd("PelisPlusHD", "https://ww1.pelisplushd.nu"),
        Pelisplusto("PelisPlusTo", "https://ww3.pelisplus.to"),
        Pelisplusph("PelisPlusPh", "https://www.pelisplushd.ph"),
    )
}

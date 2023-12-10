package eu.kanade.tachiyomi.animeextension.sr.animebalkan

import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeBalkan : AnimeStream(
    "sr",
    "AnimeBalkan",
    "https://animebalkan.org",
) {
    override val animeListUrl = "$baseUrl/animesaprevodom"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale("bs")) // YES, Bosnian
    }
}

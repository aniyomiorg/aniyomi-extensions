package eu.kanade.tachiyomi.animeextension.en.sflix

import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class SFlix : DopeFlix(
    "SFlix",
    "en",
    arrayOf("sflix.to", "sflix.se"), // Domain list
    "sflix.to", // Default domain
) {
    override val id: Long = 8615824918772726940
}

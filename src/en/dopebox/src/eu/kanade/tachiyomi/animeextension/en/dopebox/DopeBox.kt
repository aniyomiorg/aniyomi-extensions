package eu.kanade.tachiyomi.animeextension.en.dopebox

import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class DopeBox : DopeFlix(
    "DopeBox",
    "en",
    arrayOf("dopebox.to", "dopebox.se"), // Domain list
    "dopebox.to", // Default domain
) {
    override val id: Long = 787491081765201367
}

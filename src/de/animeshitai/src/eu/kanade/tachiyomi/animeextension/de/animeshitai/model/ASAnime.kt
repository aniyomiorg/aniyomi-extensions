package eu.kanade.tachiyomi.animeextension.de.animeshitai.model

import eu.kanade.tachiyomi.animesource.model.SAnime

class ASAnime : SAnime {
    override lateinit var url: String
    override lateinit var title: String
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = 0
    override var thumbnail_url: String? = null
    override var initialized: Boolean = false

    var year: Int = 0

    companion object {
        fun create(): ASAnime {
            return ASAnime()
        }
    }
}

package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias SearchDto = List<SearchEntry>

@Serializable
data class SearchEntry(
    @SerialName("_id") val id: String,
    val name: String,
    val image: String,
) {
    fun toSAnime() = SAnime.create().apply {
        title = name
        url = id
        thumbnail_url = image
    }
}

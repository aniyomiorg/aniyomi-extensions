package eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DetailsDto(
    val title: String,
    val genre: String,
    val cast: String,
    val desc: String,
    val creator: String,
    val director: String,
    val episodes: List<Episode?>? = emptyList(),
    val lang: List<LanguageDto>? = emptyList(),
) {
    val status = if (episodes?.firstOrNull() == null) {
        SAnime.COMPLETED
    } else {
        SAnime.UNKNOWN
    }
}

@Serializable
data class LanguageDto(
    @SerialName("l") val language: String,
)

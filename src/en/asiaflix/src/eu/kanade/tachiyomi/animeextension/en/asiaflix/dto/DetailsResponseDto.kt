package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DetailsResponseDto(
    @SerialName("_id") val id: String,
    val name: String,
    val altNames: String,
    val synopsis: String,
    val image: String,
    val tvStatus: String,
    val genre: String,
) {
    fun toSAnime() = SAnime.create().apply {
        title = name
        url = id
        thumbnail_url = image
        genre = this@DetailsResponseDto.genre
        status = when (tvStatus) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        description = buildString {
            append(synopsis)
            append("\n\n")

            altNames.split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString("\n") { "• $it" }
                .also { append("Alternative Names: \n$it") }
        }
        initialized = true
    }
}

package eu.kanade.tachiyomi.animeextension.it.aniplay.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PopularResponseDto(
    val data: List<PopularAnimeDto>,
    val pagination: PaginationDto,
)

@Serializable
data class PopularAnimeDto(
    val id: Int,
    @SerialName("title") val name: String,
    private val cover: String? = null,
    private val main_image: String? = null,
) {
    fun toSAnime() = SAnime.create().apply {
        url = "/series/$id"
        title = name
        thumbnail_url = cover ?: main_image
    }
}

@Serializable
data class PaginationDto(val page: Int, val pageCount: Int) {
    val hasNextPage get() = page < pageCount
}

@Serializable
data class LatestItemDto(val serie: List<PopularAnimeDto>)

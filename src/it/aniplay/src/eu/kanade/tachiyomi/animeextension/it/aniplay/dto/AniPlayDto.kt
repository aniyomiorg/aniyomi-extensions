package eu.kanade.tachiyomi.animeextension.it.aniplay.dto

import kotlinx.serialization.Serializable

@Serializable
data class PopularResponseDto(
    val data: List<PopularAnimeDto>,
    val pagination: PaginationDto,
)

@Serializable
data class PopularAnimeDto(
    val id: Int,
    val title: String,
    private val cover: String? = null,
    private val main_image: String? = null,
) {
    val thumbnailUrl = cover ?: main_image
}

@Serializable
data class PaginationDto(val page: Int, val pageCount: Int) {
    val hasNextPage get() = page < pageCount
}

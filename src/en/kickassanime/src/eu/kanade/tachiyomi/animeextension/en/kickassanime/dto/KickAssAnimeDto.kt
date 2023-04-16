package eu.kanade.tachiyomi.animeextension.en.kickassanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PopularResponseDto(
    val page_count: Int,
    val result: List<PopularItemDto>,
)

@Serializable
data class PopularItemDto(
    val title: String,
    val title_en: String,
    val slug: String,
    val poster: PosterDto,
) {
    @Serializable
    data class PosterDto(@SerialName("hq") val slug: String)

    val thumbnailPath by lazy { "image/poster/${poster.slug}.webp" }
}

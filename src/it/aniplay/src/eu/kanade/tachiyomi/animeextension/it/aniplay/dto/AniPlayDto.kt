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

@Serializable
data class AnimeInfoDto(
    val title: String,
    val description: String? = null,
    val alternative: String? = null,
    val status: String? = null,
    val origin: String? = null,
    val release_day: String? = null,
    val genres: List<NameDto> = emptyList(),
    val studios: List<NameDto> = emptyList(),
    private val cover: String? = null,
    private val main_image: String? = null,
) {
    val thumbnailUrl = cover ?: main_image
}

@Serializable
data class NameDto(val name: String)

@Serializable
data class EpisodeDto(
    val id: Int,
    val title: String? = null,
    val number: String? = null,
    val release_date: String? = null,
)

@Serializable
data class VideoDto(
    private val download_link: String? = null,
    private val streaming_link: String? = null,
) {
    val videoLink = streaming_link ?: download_link!!
}

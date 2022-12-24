package eu.kanade.tachiyomi.animeextension.pt.subanimes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResultDto(
    val animes: List<AnimeDataDto> = emptyList(),
    val errors: Int = 1,
    @SerialName("total_pages")
    val pages: Int = 0
)

@Serializable
data class AnimeDataDto(
    @SerialName("data")
    val info: AnimeInfoDto,
    val thumbnail: ThumbnailDto
)

@Serializable
data class AnimeInfoDto(
    val url: String,
    val title: String
)

@Serializable
data class ThumbnailDto(val url: String)

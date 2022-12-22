package eu.kanade.tachiyomi.animeextension.pt.sukianimes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResultDto(
    val animes: List<AnimeDto> = emptyList(),
    @SerialName("total_pages")
    val pages: Int = 0
)

@Serializable
data class AnimeDto(
    @SerialName("anime_permalink")
    val permalink: String,
    @SerialName("anime_capa")
    val thumbnail_url: String,
    @SerialName("anime_title")
    val title: String
)

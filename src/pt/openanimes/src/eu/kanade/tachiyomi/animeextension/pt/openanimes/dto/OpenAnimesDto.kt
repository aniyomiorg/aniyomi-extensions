package eu.kanade.tachiyomi.animeextension.pt.openanimes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResultDto(
    val page: String = "1",
    @SerialName("total_page")
    val totalPage: Int = 0,
    val results: List<AnimeDto> = emptyList(),
)

@Serializable
data class AnimeDto(
    val permalink: String,
    @SerialName("imagem")
    val thumbnail: String,
    @SerialName("titulo")
    val title: String,
)

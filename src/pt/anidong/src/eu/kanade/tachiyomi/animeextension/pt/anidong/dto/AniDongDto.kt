package eu.kanade.tachiyomi.animeextension.pt.anidong.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResultDto(
    val animes: List<AnimeDto>,
    @SerialName("total_pages")
    val pages: Int,
)

@Serializable
data class AnimeDto(
    @SerialName("anime_capa")
    val thumbnail_url: String,
    @SerialName("anime_permalink")
    val url: String,
    @SerialName("anime_title")
    val title: String,
)

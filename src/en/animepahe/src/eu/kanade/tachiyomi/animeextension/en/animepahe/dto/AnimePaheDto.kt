package eu.kanade.tachiyomi.animeextension.en.animepahe.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponseDto<T>(
    @SerialName("current_page")
    val currentPage: Int,
    @SerialName("last_page")
    val lastPage: Int,
    @EncodeDefault
    @SerialName("data")
    val items: List<T> = emptyList(),
)

@Serializable
data class LatestAnimeDto(
    @SerialName("anime_title")
    val title: String,
    val snapshot: String,
    @SerialName("anime_id")
    val id: Int,
    val fansub: String,
)

@Serializable
data class SearchResultDto(
    val title: String,
    val poster: String,
    val id: Int,
)

@Serializable
data class EpisodeDto(
    @SerialName("created_at")
    val createdAt: String,
    val session: String,
    @SerialName("episode")
    val episodeNumber: Float,
)

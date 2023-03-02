package eu.kanade.tachiyomi.animeextension.pt.animefire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AFResponseDto(
    @SerialName("data")
    val videos: List<VideoDto>,
)

@Serializable
data class VideoDto(
    @SerialName("src")
    val url: String,
    @SerialName("label")
    val quality: String,
)

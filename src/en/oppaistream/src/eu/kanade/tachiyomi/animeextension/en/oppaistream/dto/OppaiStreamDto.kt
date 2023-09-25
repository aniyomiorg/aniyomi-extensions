package eu.kanade.tachiyomi.animeextension.en.oppaistream.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnilistResponseDto(val data: DataDto)

@Serializable
data class DataDto(@SerialName("Media") val media: MediaDto?)

@Serializable
data class MediaDto(val coverImage: CoverDto, val studios: StudiosDto)

@Serializable
data class CoverDto(val extraLarge: String, val large: String)

@Serializable
data class StudiosDto(val nodes: List<NodeDto>) {
    @Serializable
    data class NodeDto(val name: String)

    val names by lazy { nodes.map { it.name } }
}

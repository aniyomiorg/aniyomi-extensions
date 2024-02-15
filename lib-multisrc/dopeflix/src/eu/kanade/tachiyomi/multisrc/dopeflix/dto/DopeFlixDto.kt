package eu.kanade.tachiyomi.multisrc.dopeflix.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VideoDto(
    val sources: List<VideoLink>,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class SourceResponseDto(
    val sources: JsonElement,
    val encrypted: Boolean = true,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class VideoLink(val file: String = "")

@Serializable
data class TrackDto(val file: String, val kind: String, val label: String = "")

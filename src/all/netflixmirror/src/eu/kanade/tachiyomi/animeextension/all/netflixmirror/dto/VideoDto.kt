package eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto

import kotlinx.serialization.Serializable

typealias VideoList = List<VideoDto>

@Serializable
data class VideoDto(
    val sources: List<PlayList>,
)

@Serializable
data class PlayList(
    val file: String,
)

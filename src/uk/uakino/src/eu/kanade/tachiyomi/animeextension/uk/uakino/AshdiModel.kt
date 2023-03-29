package eu.kanade.tachiyomi.animeextension.uk.uakino

import kotlinx.serialization.Serializable

@Serializable
data class AshdiModel(
    val title: String,
    val folder: List<Ashdi>,
)

@Serializable
data class Ashdi(
    val title: String,
    val folder: List<Video>,
)

@Serializable
data class Video(
    val title: String,
    val file: String,
    val id: String,
    val poster: String,
    val subtitle: String,
)

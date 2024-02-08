package eu.kanade.tachiyomi.animeextension.all.debridindex.dto

import kotlinx.serialization.Serializable

// Root
@Serializable
data class RootFiles(
    val metas: List<Meta>? = null,
)

@Serializable
data class SubFiles(
    val meta: Meta? = null,
)

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val videos: List<Video>? = null,
)

@Serializable
data class Video(
    val id: String,
    val title: String,
    val released: String,
    val streams: List<Stream>,
)

@Serializable
data class Stream(
    val url: String,
)

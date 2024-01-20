package eu.kanade.tachiyomi.animeextension.en.putlocker

import kotlinx.serialization.Serializable

@Serializable
data class EpLinks(
    val dataId: String,
    val mediaId: String,
)

@Serializable
data class EpResp(
    val status: Boolean,
    val src: String,
)

@Serializable
data class VidSource(
    val file: String,
    val type: String?,
)

@Serializable
data class SubTrack(
    val file: String,
    val label: String?,
)

@Serializable
data class Sources(
    val sources: List<VidSource>,
    val tracks: List<SubTrack>?,
    val backupLink: String?,
)

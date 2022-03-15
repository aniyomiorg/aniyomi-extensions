package eu.kanade.tachiyomi.animeextension.pt.animesvision

import kotlinx.serialization.Serializable

@Serializable
data class AVResponseDto(
    val serverMemo: AVResponseMemo? = null
)

@Serializable
data class AVResponseMemo(
    val data: AVResponseData? = null,
)

@Serializable
data class AVResponseData(
    val framePlay: String? = null,
)

package eu.kanade.tachiyomi.animeextension.pt.animesvision.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
data class PayloadItem(
    val payload: PayloadData,
    @EncodeDefault
    val type: String = "callMethod"
)

@Serializable
data class PayloadData(
    val params: List<Int>,
    @EncodeDefault
    val method: String = "mudarPlayer",
    @EncodeDefault
    val id: String = ""
)

@Serializable
data class AVResponseDto(
    val effects: AVResponseEffects? = null,
    val serverMemo: AVResponseMemo? = null
)

@Serializable
data class AVResponseEffects(
    val html: String? = null
)

@Serializable
data class AVResponseMemo(
    val data: AVResponseData? = null,
)

@Serializable
data class AVResponseData(
    val framePlay: String? = null,
)

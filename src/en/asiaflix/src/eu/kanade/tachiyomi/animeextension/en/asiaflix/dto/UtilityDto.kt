package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamHeadDto(
    @SerialName("stream_source") val source: String,
)

class UtilityDto

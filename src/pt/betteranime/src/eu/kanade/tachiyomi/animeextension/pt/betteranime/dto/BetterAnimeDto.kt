package eu.kanade.tachiyomi.animeextension.pt.betteranime.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChangePlayerDto(val frameLink: String? = null)

@Serializable
data class ComponentsDto(val components: List<LivewireResponseDto>)

@Serializable
data class LivewireResponseDto(val effects: LivewireEffects)

@Serializable
data class LivewireEffects(val html: String? = null)

@Serializable
data class PayloadData(
    val method: String = "",
    @EncodeDefault val params: List<JsonElement> = emptyList(),
    @EncodeDefault val path: String = "",
)

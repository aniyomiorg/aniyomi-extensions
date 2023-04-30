package eu.kanade.tachiyomi.animeextension.pt.flixei.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResultsDto<T>(
    @SerialName("list")
    val items: Map<String, T>,
)

@Serializable
data class AnimeDto(val id: String, val title: String, val url: String)

@Serializable
data class EpisodeDto(val id: String, val name: String)

@Serializable
data class PlayersDto(
    val id: String,
    val audio: String,
    val mixdropStatus: String = "0",
    val streamtapeStatus: String = "0",
    val warezcdnStatus: String = "0",
) {
    operator fun iterator(): List<Pair<String, String>> {
        return listOf(
            "streamtape" to streamtapeStatus,
            "mixdrop" to mixdropStatus,
            "warezcdn" to warezcdnStatus,
        )
    }
}

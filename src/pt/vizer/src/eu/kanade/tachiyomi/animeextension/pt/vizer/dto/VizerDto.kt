package eu.kanade.tachiyomi.animeextension.pt.vizer.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class SearchResultDto(
    val quantity: Int = 0,
    @Serializable(with = GenericListSerializer::class)
    @EncodeDefault
    val list: List<SearchItemDto> = emptyList()
)

@Serializable
data class SearchItemDto(
    val id: String,
    val title: String,
    val url: String,
    @EncodeDefault
    val status: String = ""
)

@Serializable
data class EpisodeListDto(
    @Serializable(with = GenericListSerializer::class)
    @SerialName("list")
    val episodes: List<EpisodeItemDto>
)

@Serializable
data class EpisodeItemDto(
    val id: String,
    val img: String,
    val name: String,
    val released: Boolean,
    val title: String
)

@Serializable
data class VideoLanguagesDto(
    @SerialName("list")
    @Serializable(with = GenericListSerializer::class)
    val videos: List<VideoDto>
)

@Serializable
data class VideoDto(
    val id: String,
    val lang: String
)

@Serializable
data class PlayersDto(
    val mixdrop: String = "0",
    val streamtape: String = "0",
    val fembed: String = "0"
) {
    operator fun iterator(): List<Pair<String, String>> {
        return listOf(
            "mixdrop" to mixdrop,
            "streamtape" to streamtape,
            "fembed" to fembed
        )
    }
}

class GenericListSerializer<T>(
    private val itemSerializer: KSerializer<T>
) : JsonTransformingSerializer<List<T>>(ListSerializer(itemSerializer)) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val jsonObj = element as JsonObject
        return JsonArray(jsonObj.values.toList())
    }
}

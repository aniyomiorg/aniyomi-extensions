package eu.kanade.tachiyomi.animeextension.pt.vizer.dto

import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

typealias FakeList<T> = Map<String, T>

@Serializable
class SearchResultDto(
    val quantity: Int = 0,
    @EncodeDefault
    @SerialName("list")
    val items: FakeList<SearchItemDto> = emptyMap(),
)

@Serializable
class SearchItemDto(
    val id: String,
    val title: String,
    val url: String,
    @EncodeDefault
    val status: String = "",
)

@Serializable
class EpisodeListDto(
    @SerialName("list")
    val episodes: FakeList<EpisodeItemDto>,
)

@Serializable
class EpisodeItemDto(
    val id: String,
    val name: String,
    @Serializable(with = BooleanSerializer::class)
    val released: Boolean,
    val title: String,
)

@Serializable
class VideoListDto(
    @SerialName("list")
    val videos: FakeList<VideoDto>,
)

@Serializable
class VideoDto(
    val id: String,
    val lang: String,
    @SerialName("players")
    private val players: String? = null,
) {
    var hosters = try {
        players?.parseAs<HostersDto>()
    } catch (e: Throwable) {
        null
    }
}

@Serializable
class HostersDto(
    val mixdrop: Int = 0,
    val streamtape: Int = 0,
    val warezcdn: Int = 0,
) {
    operator fun iterator(): List<Pair<String, Int>> {
        return listOf(
            "mixdrop" to mixdrop,
            "streamtape" to streamtape,
            "warezcdn" to warezcdn,
        )
    }
}

object BooleanSerializer : JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        require(element is JsonPrimitive)
        return if (element.jsonPrimitive.isString) {
            JsonPrimitive(true)
        } else {
            JsonPrimitive(element.jsonPrimitive.booleanOrNull ?: false)
        }
    }
}

package eu.kanade.tachiyomi.animeextension.pt.betteranime.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class ChangePlayerDto(
    val frameLink: String? = null
)

@Serializable
data class LivewireResponseDto(
    val effects: LivewireEffects
)

@Serializable
data class LivewireEffects(
    val html: String? = null
)

@ExperimentalSerializationApi
@Serializable
data class PayloadItem(
    val payload: PayloadData,
    val type: String
)

@ExperimentalSerializationApi
@Serializable
data class PayloadData(
    val name: String = "",
    val method: String = "",
    @Serializable(with = ValueSerializer::class)
    val value: List<String> = emptyList<String>(),
    @EncodeDefault val params: List<JsonElement> = emptyList<JsonElement>(),
    @EncodeDefault val id: String = ""
)

object ValueSerializer : JsonTransformingSerializer<List<String>>(
    ListSerializer(String.serializer())
) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        require(element is JsonArray)
        if (element.size > 1)
            return JsonArray(element.drop(1))
        return element.first()
    }
}

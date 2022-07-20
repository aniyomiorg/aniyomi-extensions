package eu.kanade.tachiyomi.animeextension.pt.animeyabu

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class SearchResultDto(
    val title: String,
    val genre: String,
    @Serializable(with = IntSerializer::class)
    val videos: Int,
    val cover: String,
    val type: String,
    val slug: String
)

object IntSerializer : JsonTransformingSerializer<Int>(Int.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        return try {
            JsonPrimitive(element.toString().toInt())
        } catch (e: Exception) { JsonPrimitive(-1) }
    }
}

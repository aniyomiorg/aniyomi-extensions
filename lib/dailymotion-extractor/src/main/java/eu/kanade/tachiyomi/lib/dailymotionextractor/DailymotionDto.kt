package eu.kanade.tachiyomi.lib.dailymotionextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class DailyQuality(
    val qualities: Auto? = null,
    val subtitles: Subtitle? = null,
    val error: Error? = null,
    val id: String? = null,
) {
    @Serializable
    data class Error(val type: String)
}

@Serializable
data class Auto(val auto: List<Item>) {
    @Serializable
    data class Item(val type: String, val url: String)
}

@Serializable
data class Subtitle(
    @Serializable(with = SubtitleListSerializer::class)
    val data: List<SubtitleDto>,
)

@Serializable
data class SubtitleDto(val label: String, val urls: List<String>)

object SubtitleListSerializer :
    JsonTransformingSerializer<List<SubtitleDto>>(ListSerializer(SubtitleDto.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonArray(element.values.toList())
            else -> JsonArray(emptyList())
        }
}

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String,
)

@Serializable
data class ProtectedResponse(val data: DataObject) {
    @Serializable
    data class DataObject(val video: VideoObject) {
        @Serializable
        data class VideoObject(
            val id: String,
            val xid: String,
        )
    }
}

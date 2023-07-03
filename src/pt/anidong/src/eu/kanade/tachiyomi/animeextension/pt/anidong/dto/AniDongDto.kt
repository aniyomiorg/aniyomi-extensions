package eu.kanade.tachiyomi.animeextension.pt.anidong.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class SearchResultDto(
    val animes: List<AnimeDto>,
    @SerialName("total_pages")
    val pages: Int,
)

@Serializable
data class AnimeDto(
    @SerialName("anime_capa")
    val thumbnail_url: String,
    @SerialName("anime_permalink")
    val url: String,
    @SerialName("anime_title")
    val title: String,
)

@Serializable
data class EpisodeListDto(
    @Serializable(with = EpisodeListSerializer::class)
    @SerialName("episodios")
    val episodes: List<EpisodeDto>,
    @Serializable(with = EpisodeListSerializer::class)
    @SerialName("filmes")
    val movies: List<EpisodeDto>,
    @Serializable(with = EpisodeListSerializer::class)
    val ovas: List<EpisodeDto>,
)

@Serializable
data class EpisodeDto(
    val epi_num: String,
    val epi_url: String,
)

object EpisodeListSerializer :
    JsonTransformingSerializer<List<EpisodeDto>>(ListSerializer(EpisodeDto.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonArray(element.values.toList())
            else -> JsonArray(emptyList())
        }
}

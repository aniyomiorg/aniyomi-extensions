package eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class SearchDto(
    val results: List<AnimeDto>,
)

@Serializable
data class AnimeDto(
    @SerialName("descricao")
    val description: String,
    @SerialName("generos")
    val genres: List<GenreDto>?,
    @SerialName("id_animes")
    val id: Int,
    @SerialName("nome")
    val name: String,
    @SerialName("card")
    val thumbnail: String,
)

@Serializable
data class GenreDto(
    @SerialName("descricao")
    val name: String,
)

@Serializable
data class SeasonListDto(
    @SerialName("results")
    val seasons: List<SeasonInfoDto>,
)

@Serializable
data class SeasonInfoDto(
    @SerialName("id_temporadas")
    val id: Int,
    @SerialName("nome")
    val name: String,
    @SerialName("numero")
    val number: String,
)

@Serializable
data class EpisodeDataDto(
    @SerialName("results")
    val episodes: List<EpisodeDto>,
)

@Serializable
data class EpisodeDto(
    @SerialName("numero")
    val ep_number: String,
    @SerialName("id_episodios")
    val id: Int,
    @SerialName("nome")
    val name: String,
    @SerialName("lancamento")
    val release_date: String,
)

@Serializable
data class EpisodeVideoDto(
    val streams: List<VideoDto>? = null,
    @SerialName("legenda")
    val subUrl: String? = null,
)

@Serializable
data class MinimalEpisodeDto(
    @SerialName("temporada")
    val season: MinimalSeasonDto? = null,
    val url: String = "",
)

@Serializable
data class MinimalSeasonDto(
    val anime: AnimeDto,
)

@Serializable
data class VideoLinksDto(
    val softsub: SubtitleDto,
    @Serializable(with = SubtitleSerializer::class)
    val hardsub: List<SubtitleDto>,
    @Serializable(with = SubtitleSerializer::class)
    val subtitles: List<SubtitleDto>,
)

@Serializable
data class SubtitleDto(
    val url: String,
    @JsonNames("locale", "hardsub_locale")
    val language: String,
)

@Serializable
data class VideoDto(
    @SerialName("resolucao")
    val quality: List<Int>,
    val url: String,
)

object SubtitleSerializer : JsonTransformingSerializer<List<SubtitleDto>>(
    ListSerializer(SubtitleDto.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        require(element is JsonObject)
        return JsonArray(element.values.toList())
    }
}

package eu.kanade.tachiyomi.animeextension.pt.puraymoe.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchDto(
    val results: List<AnimeDto>
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
    val thumbnail: String
)

@Serializable
data class GenreDto(
    @SerialName("descricao")
    val name: String
)

@Serializable
data class SeasonListDto(
    @SerialName("results")
    val seasons: List<SeasonInfoDto>
)

@Serializable
data class SeasonInfoDto(
    @SerialName("id_temporadas")
    val id: Int,
    @SerialName("nome")
    val name: String,
    @SerialName("numero")
    val number: String
)

@Serializable
data class EpisodeDataDto(
    @SerialName("results")
    val episodes: List<EpisodeDto>
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
    val subUrl: String? = null
)

@Serializable
data class MinimalEpisodeDto(
    @SerialName("temporada")
    val season: MinimalSeasonDto? = null,
    val streams: List<VideoDto>? = null,
    val url: String = ""
)

@Serializable
data class MinimalSeasonDto(
    val anime: AnimeDto
)

@Serializable
data class VideoDto(
    @SerialName("resolucao")
    val quality: List<Int>,
    val url: String
)

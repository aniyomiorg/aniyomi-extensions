package eu.kanade.tachiyomi.animeextension.pt.animesroll.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class PagePropDto<T>(val pageProps: DataPropDto<T>) {
    val data by lazy { pageProps.data }
}

@Serializable
data class DataPropDto<T>(val data: T)

@Serializable
data class LatestAnimeDto(
    @SerialName("data_releases")
    val episodes: List<EpisodeAnimeDto>,
) {
    @Serializable
    data class EpisodeAnimeDto(val episode: EpisodeDto)
}

@Serializable
data class MovieInfoDto(
    @SerialName("data_movie")
    val movieData: AnimeDataDto,
)

@Serializable
data class AnimeDataDto(
    @SerialName("diretor")
    val director: String = "",
    @JsonNames("nome_filme", "titulo")
    val anititle: String,
    @JsonNames("sinopse", "sinopse_filme")
    val description: String = "",
    @SerialName("slug_serie")
    val slug: String = "",
    @SerialName("slug_filme")
    val slug_movie: String = "",
    @SerialName("duracao")
    val duration: String = "",
    @SerialName("generate_id")
    val id: String = "",
    val animeCalendar: String? = null,
    val od: String = "",
)

@Serializable
data class EpisodeListDto(
    @SerialName("data")
    val episodes: List<EpisodeDto>,
    val meta: MetadataDto,
) {
    @Serializable
    data class MetadataDto(val totalOfPages: Int)
}

@Serializable
data class EpisodeDto(
    @SerialName("n_episodio")
    val episodeNumber: String,
    val anime: AnimeDataDto? = null,
)

@Serializable
data class SearchResultsDto(
    @SerialName("data_anime")
    val animes: List<AnimeDataDto>,
    @SerialName("data_filme")
    val movies: List<AnimeDataDto>,
)

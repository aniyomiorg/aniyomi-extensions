package eu.kanade.tachiyomi.animeextension.all.sudatchi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GenreDto(val name: String)

@Serializable
data class AnimeGenreRelationDto(
    @SerialName("Genre")
    val genre: GenreDto,
)

@Serializable
data class EpisodeDto(
    val title: String,
    val id: Int,
    val number: Int,
)

@Serializable
data class AnimeDto(
    val titleRomanji: String?,
    val titleEnglish: String?,
    val titleJapanese: String?,
    val synopsis: String,
    val slug: String,
    val statusId: Int,
    val imgUrl: String,
    @SerialName("AnimeGenres")
    val animeGenres: List<AnimeGenreRelationDto>?,
    @SerialName("Episodes")
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class HomePageDto(
    @SerialName("AnimeSpotlight")
    val animeSpotlight: List<AnimeDto>,
)

@Serializable
data class AnimePageDto(
    val animeData: AnimeDto,
)

@Serializable
data class EpisodeDataDto(
    val episode: EpisodeDto,
    val subtitlesJson: String,
)

@Serializable
data class EpisodePageDto(
    val episodeData: EpisodeDataDto,
)

@Serializable
data class PagePropsDto<T>(val pageProps: T)

@Serializable
data class PropsDto<T>(val props: PagePropsDto<T>)

@Serializable
data class DirectoryDto(
    val animes: List<AnimeDto>,
    val page: Int,
    val pages: Int,
)

@Serializable
data class SubtitleLangDto(
    val name: String,
    val language: String,
)

@Serializable
data class SubtitleDto(
    val url: String,
    @SerialName("SubtitlesName")
    val subtitlesName: SubtitleLangDto,
)

@Serializable
data class FilterItemDto(
    val id: Int,
    val name: String,
)

@Serializable
data class FilterYearDto(
    val year: Int,
)

@Serializable
data class DirectoryFiltersDto(
    val genres: List<FilterItemDto>,
    val years: List<FilterYearDto>,
    val types: List<FilterItemDto>,
    val status: List<FilterItemDto>,
)

@Serializable
data class StreamsDto(
    val url: String,
)

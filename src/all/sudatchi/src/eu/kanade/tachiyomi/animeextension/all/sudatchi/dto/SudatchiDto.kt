package eu.kanade.tachiyomi.animeextension.all.sudatchi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Genre(val name: String)

@Serializable
data class AnimeGenreRelation(
    @SerialName("Genre")
    val genre: Genre,
)

@Serializable
data class ShortAnimeDto(
    val titleRomanji: String?,
    val titleEnglish: String?,
    val titleJapanese: String?,
    val synopsis: String,
    val slug: String,
    val statusId: Int,
    val imgUrl: String,
    @SerialName("AnimeGenres")
    val animeGenres: List<AnimeGenreRelation>?,
)

@Serializable
data class HomeListDto(
    @SerialName("AnimeSpotlight")
    val animeSpotlight: List<ShortAnimeDto>,
)

@Serializable
data class DirectoryDto(
    val animes: List<ShortAnimeDto>,
    val page: Int,
    val pages: Int,
)

@Serializable
data class Episode(
    val title: String,
    val id: Int,
    val number: Int,
)

@Serializable
data class LongAnimeDto(
    val slug: String,
    @SerialName("Episodes")
    val episodes: List<Episode>,
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
data class EpisodeDataDto(
    val episode: Episode,
    val subtitlesJson: String,
)

@Serializable
data class PagePropsDto(
    val episodeData: EpisodeDataDto,
)

@Serializable
data class DataWatchDto(
    val pageProps: PagePropsDto,
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

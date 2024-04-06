package eu.kanade.tachiyomi.animeextension.es.doramasflix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -----------------------Season models------------------------//
@Serializable
data class SeasonModel(
    val data: DataSeason = DataSeason(),
)

@Serializable
data class DataSeason(
    val listSeasons: List<ListSeason> = emptyList(),
)

@Serializable
data class ListSeason(
    val slug: String,
    @SerialName("season_number")
    val seasonNumber: Long,
    @SerialName("poster_path")
    val posterPath: String?,
    @SerialName("air_date")
    val airDate: String?,
    @SerialName("serie_name")
    val serieName: String?,
    val poster: String?,
    @SerialName("__typename")
    val typename: String,
)

// -----------------------Episode Model------------------------//
@Serializable
data class EpisodeModel(
    val data: DataEpisode = DataEpisode(),
)

@Serializable
data class DataEpisode(
    val listEpisodes: List<ListEpisode> = emptyList(),
)

@Serializable
data class ListEpisode(
    @SerialName("_id")
    val id: String,
    val name: String?,
    val slug: String,
    @SerialName("serie_name")
    val serieName: String?,
    @SerialName("serie_name_es")
    val serieNameEs: String?,
    @SerialName("air_date")
    val airDate: String?,
    @SerialName("season_number")
    val seasonNumber: Long?,
    @SerialName("episode_number")
    val episodeNumber: Long?,
    val poster: String?,
    @SerialName("__typename")
    val typename: String,
)

// -----------------------Pagination Model------------------------//

@Serializable
data class PaginationModel(
    val data: DataPagination = DataPagination(),
)

@Serializable
data class DataPagination(
    val paginationDorama: PaginationDorama? = null,
    val paginationMovie: PaginationDorama? = null,
)

@Serializable
data class PaginationDorama(
    val count: Long,
    val pageInfo: PageInfo,
    val items: List<Item> = emptyList(),
    @SerialName("__typename")
    val typename: String,
)

@Serializable
data class PageInfo(
    val currentPage: Long,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean,
    @SerialName("__typename")
    val typename: String,
)

@Serializable
data class Item(
    @SerialName("_id")
    val id: String,
    val name: String,
    @SerialName("name_es")
    val nameEs: String?,
    val slug: String,
    val names: String?,
    val overview: String?,
    @SerialName("poster_path")
    val posterPath: String?,
    val poster: String?,
    val genres: List<Genre> = emptyList(),
    @SerialName("__typename")
    val typename: String,
)

@Serializable
data class Genre(
    val name: String?,
    val slug: String?,
    @SerialName("__typename")
    val typename: String?,
)

// -----------------------Search Model------------------------//
@Serializable
data class SearchModel(
    val data: Data = Data(),
)

@Serializable
data class Data(
    val searchDorama: List<SearchDorama> = emptyList(),
    val searchMovie: List<SearchDorama> = emptyList(),
)

@Serializable
data class SearchDorama(
    @SerialName("_id")
    val id: String,
    val slug: String,
    val name: String,
    @SerialName("name_es")
    val nameEs: String?,
    @SerialName("poster_path")
    val posterPath: String?,
    val poster: String?,
    @SerialName("__typename")
    val typename: String,
)

// -------------------------------------------------------

@Serializable
data class VideoToken(
    val link: String?,
    val server: String?,
    val app: String?,
    val iat: Long?,
    val exp: Long?,
)

// ------------------------------------------------

@Serializable
data class TokenModel(
    val props: PropsToken = PropsToken(),
    val page: String? = null,
    val query: QueryToken = QueryToken(),
    val buildId: String? = null,
    val isFallback: Boolean? = false,
    val isExperimentalCompile: Boolean? = false,
    val gssp: Boolean? = false,
)

@Serializable
data class PropsToken(
    val pageProps: PagePropsToken = PagePropsToken(),
    @SerialName("__N_SSP")
    val nSsp: Boolean? = false,
)

@Serializable
data class PagePropsToken(
    val token: String? = null,
    val name: String? = null,
    val app: String? = null,
    val server: String? = null,
    val iosapp: String? = null,
    val externalLink: String? = null,
)

@Serializable
data class QueryToken(
    val token: String? = null,
)

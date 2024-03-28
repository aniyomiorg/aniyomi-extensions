package eu.kanade.tachiyomi.animeextension.es.doramasflix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    val backdrop: String?,
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
    @SerialName("serie_id")
    val serieId: String?,
    @SerialName("still_path")
    val stillPath: String?,
    @SerialName("air_date")
    val airDate: String?,
    @SerialName("season_number")
    val seasonNumber: Long?,
    @SerialName("episode_number")
    val episodeNumber: Long?,
    // val languages: List<Any?>,
    val poster: String?,
    val backdrop: String?,
    @SerialName("__typename")
    val typename: String,
)

// -----------------------Details Model------------------------//

data class DetailsModel(
    val props: Props,
    val page: String,
    val query: Query,
    val buildId: String,
    val isFallback: Boolean,
    val gip: Boolean,
)

data class Props(
    val pageProps: PageProps,
)

data class PageProps(
    val deviceType: String,
    val slug: String,
    // val apolloClient: Any?,
    val apolloState: HashMap<String, HashMap<String, JsonObject>>,
    val ssrComplete: Boolean,
)

data class Query(
    val slug: String,
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
    val cast: List<Cast> = emptyList(),
    val names: String?,
    val overview: String?,
    val languages: List<String?>? = emptyList(),
    @SerialName("created_by")
    val createdBy: List<CreatedBy> = emptyList(),
    val popularity: Double?,
    @SerialName("poster_path")
    val posterPath: String?,
    @SerialName("backdrop_path")
    val backdropPath: String?,
    @SerialName("first_air_date")
    val firstAirDate: String?,
    @SerialName("isTVShow")
    val isTvshow: Boolean?,
    val poster: String?,
    val backdrop: String?,
    val genres: List<Genre> = emptyList(),
    val networks: List<Network> = emptyList(),
    @SerialName("__typename")
    val typename: String,
)

@Serializable
data class Cast(
    val adult: Boolean?,
    val gender: Long?,
    val id: Long?,
    @SerialName("known_for_department")
    val knownForDepartment: String?,
    val name: String?,
    @SerialName("original_name")
    val originalName: String?,
    val popularity: Double?,
    @SerialName("profile_path")
    val profilePath: String?,
    val character: String?,
    @SerialName("credit_id")
    val creditId: String?,
    val order: Long?,
)

@Serializable
data class CreatedBy(
    val adult: Boolean?,
    val gender: Long?,
    val id: Long?,
    @SerialName("known_for_department")
    val knownForDepartment: String?,
    val name: String?,
    @SerialName("original_name")
    val originalName: String?,
    val popularity: Double?,
    @SerialName("profile_path")
    val profilePath: String?,
    @SerialName("credit_id")
    val creditId: String?,
    val department: String?,
    val job: String?,
)

@Serializable
data class Genre(
    val name: String?,
    val slug: String?,
    @SerialName("__typename")
    val typename: String?,
)

@Serializable
data class Network(
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
data class VideoModel(
    val json: JsonVideo = JsonVideo(),
)

@Serializable
data class JsonVideo(
    val lang: String? = "",
    val page: String? = "",
    val link: String? = "",
    val server: String? = "",
)

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
    // val scriptLoader: List<Any?>,
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

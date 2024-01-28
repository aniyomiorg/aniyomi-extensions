package eu.kanade.tachiyomi.animeextension.en.seez

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbResponse(
    val page: Int,
    @SerialName("total_pages")
    val totalPages: Int,
    val results: List<TmdbResult>,
) {
    @Serializable
    data class TmdbResult(
        val id: Int,
        @SerialName("media_type")
        val mediaType: String = "tv",
        @SerialName("poster_path")
        val posterPath: String? = null,
        val title: String? = null,
        val name: String? = null,
    )
}

@Serializable
data class TmdbDetailsResponse(
    val id: Int,
    val overview: String? = null,
    val genres: List<GenreObject>? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("first_air_date")
    val firstAirDate: String? = null,
    @SerialName("last_air_date")
    val lastAirDate: String? = null,
    val name: String? = null,
    val title: String? = null,
    val seasons: List<SeasonObject> = emptyList(),
    val status: String,
    @SerialName("next_episode_to_air")
    val nextEpisode: NextEpisode? = null,
    @SerialName("production_companies")
    val productions: List<Company>? = null,
    @SerialName("spoken_languages")
    val languages: List<Language>? = null,
) {
    @Serializable
    data class GenreObject(
        val name: String,
    )

    @Serializable
    data class SeasonObject(
        @SerialName("season_number")
        val seasonNumber: Int,
    )

    @Serializable
    data class NextEpisode(
        val name: String? = "",
        @SerialName("episode_number")
        val epNumber: Int,
        @SerialName("air_date")
        val airDate: String,
    )

    @Serializable
    data class Company(
        val name: String,
        @SerialName("origin_country")
        val originCountry: String,
    )

    @Serializable
    data class Language(
        val name: String,
        @SerialName("english_name")
        val engName: String,
    )
}

@Serializable
data class TmdbSeasonResponse(
    val episodes: List<EpisodeObject>,
) {
    @Serializable
    data class EpisodeObject(
        @SerialName("episode_number")
        val epNumber: Int,
        val name: String,
        @SerialName("air_date")
        val airDate: String? = null,
    )
}

@Serializable
data class LinkData(
    val id: Int,
    @SerialName("media_type")
    val mediaType: String,
)

@Serializable
data class EmbedSourceList(
    val result: List<EmbedSource>,
) {
    @Serializable
    data class EmbedSource(
        val id: String,
        val title: String,
    )
}

@Serializable
data class EmbedUrlResponse(
    val result: EmbedUrlObject,
) {
    @Serializable
    data class EmbedUrlObject(
        val url: String,
    )
}

package eu.kanade.tachiyomi.animeextension.en.seez

import kotlinx.serialization.Serializable

@Serializable
data class TmdbResponse(
    val page: Int,
    val total_pages: Int,
    val results: List<TmdbResult>,
) {
    @Serializable
    data class TmdbResult(
        val id: Int,
        val media_type: String = "tv",
        val poster_path: String? = null,
        val title: String? = null,
        val name: String? = null,
    )
}

@Serializable
data class TmdbDetailsResponse(
    val id: Int,
    val overview: String? = null,
    val genres: List<GenreObject>? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val last_air_date: String? = null,
    val name: String? = null,
    val title: String? = null,
    val seasons: List<SeasonObject> = emptyList(),
) {
    @Serializable
    data class GenreObject(
        val name: String,
    )

    @Serializable
    data class SeasonObject(
        val season_number: Int,
        // id	787
        // name	"Book Two: Earth"
    )
}

@Serializable
data class TmdbSeasonResponse(
    val episodes: List<EpisodeObject>,
) {
    @Serializable
    data class EpisodeObject(
        val episode_number: Int,
        val name: String,
        val air_date: String? = null,
    )
}

@Serializable
data class LinkData(
    val id: Int,
    val media_type: String,
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

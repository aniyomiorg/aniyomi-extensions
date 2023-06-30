package eu.kanade.tachiyomi.animeextension.ar.animeiat.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PopularAnimeResponse(
    @SerialName("data")
    val `data`: List<PopularAnimeList>,
    val links: Links,
    val meta: Meta,
)

@Serializable
data class LatestAnimeResponse(
    @SerialName("data")
    val `data`: List<EpisodesList>,
    val links: Links,
    val meta: Meta,
)

@Serializable
data class PopularAnimeList(
    val anime_name: String,
    val poster_path: String,
    val slug: String,
)

@Serializable
data class Links(
    val first: String?,
    val last: String?,
    val next: String?,
    val prev: String?,
)

@Serializable
data class Meta(
    val current_page: Int,
    val last_page: Int,
)

@Serializable
data class AnimePageResponse(
    @SerialName("data")
    val `data`: AnimeDetails,
)

@Serializable
data class AnimeDetails(
    val anime_name: String,
    val genres: List<Genre>,
    val poster_path: String,
    val slug: String,
    val status: String,
    val story: String,
    val studios: List<Studio>,
)

@Serializable
data class Genre(val name: String)

@Serializable
data class Studio(val name: String)

@Serializable
data class AnimeEpisodesList(
    val `data`: List<EpisodesList>,
    val links: Links,
)

@Serializable
data class EpisodesList(
    val number: Float,
    val slug: String,
    val title: String,
    val poster_path: String,
)

@Serializable
data class StreamLinks(
    val `data`: VideoInformation,
)

@Serializable
data class VideoInformation(
    val sources: List<Sources>,
)

@Serializable
data class Sources(
    val `file`: String,
    val label: String,
    val name: String,
    val newfile: String,
    val quality: String,
)

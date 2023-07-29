package eu.kanade.tachiyomi.animeextension.ar.arabanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PopularAnimeResponse(
    val Shows: List<String>,
    val current_page: Int,
    val last_page: Int,
)

@Serializable
data class AnimeItem(
    val anime_cover_image_url: String,
    val anime_id: String,
    val anime_name: String,
    val anime_score: String,
    val anime_slug: String,
    val anime_type: String,
    val info_src: String,
)

@Serializable
data class ShowItem(
    val EPS: List<EPS>,
    val show: List<Show>,
)

@Serializable
data class EPS(
    val episode_name: String,
    val episode_number: Int,
    @SerialName("info-src")
    val `info-src`: String,
)

@Serializable
data class Show(
    val anime_cover_image_url: String,
    val anime_description: String,
    val anime_genres: String,
    val anime_id: Int,
    val anime_name: String,
    val anime_release_date: String,
    val anime_score: String,
    val anime_slug: String,
    val anime_status: String,
    val anime_type: String,
    val show_episode_count: Int,
    val wallpapaer: String,
)

@Serializable
data class Episode(
    val ep_info: List<EpInfo>,
)

@Serializable
data class EpInfo(
    val stream_servers: List<String>,
)

package eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Normal Anilst Meta Data
@Serializable
data class AnilistMeta(
    val data: AnilistPageData? = null,
)

@Serializable
data class AnilistPageData(
    @SerialName("Page")
    val page: AnilistPage? = null,
)

@Serializable
data class AnilistPage(
    val pageInfo: AnilistPageInfo? = null,
    val media: List<AnilistMedia>? = null,
)

// For Latest
@Serializable
data class AnilistMetaLatest(
    val data: AnilistPageDataLatest? = null,
)

@Serializable
data class AnilistPageDataLatest(
    @SerialName("Page")
    val page: AnilistPageLatest? = null,
)

@Serializable
data class AnilistPageLatest(
    val pageInfo: AnilistPageInfo? = null,
    val airingSchedules: List<AnilistAiringSchedule>? = null, // Change this line
)

@Serializable
data class AnilistAiringSchedule(
    val media: AnilistMedia? = null,
)

@Serializable
data class AnilistPageInfo(
    val currentPage: Int = 0,
    val hasNextPage: Boolean = false,
)

// For Details
@Serializable
data class DetailsById(
    val data: DetailsByIdData? = null,
)

@Serializable
data class DetailsByIdData(
    @SerialName("Media")
    val media: AnilistMedia? = null,
)

// Re-usable Media
@Serializable
data class AnilistMedia(
    val id: Int? = null,
    val siteUrl: String? = null,
    val title: AnilistTitle? = null,
    val coverImage: AnilistCoverImage? = null,
    val description: String? = null,
    val status: String? = null,
    val tags: List<AnilistTag>? = null,
    val genres: List<String>? = null,
    val studios: AnilistStudios? = null,
    val countryOfOrigin: String? = null,
    val isAdult: Boolean = false,
)

@Serializable
data class AnilistTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AnilistCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
)

@Serializable
data class AnilistStudios(
    val nodes: List<AnilistNode>? = null,
)

@Serializable
data class AnilistNode(
    val name: String? = null,
)

@Serializable
data class AnilistTag(
    val name: String? = null,
)

// Stream Data For Torrent
@Serializable
data class StreamDataTorrent(
    val streams: List<TorrentioStream>? = null,
)

@Serializable
data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val behaviorHints: BehaviorHints? = null,
)

@Serializable
data class BehaviorHints(
    val bingeGroup: String? = null,
)

// Episode Data

@Serializable
data class EpisodeList(
    val meta: EpisodeMeta? = null,
)

@Serializable
data class EpisodeMeta(
    @SerialName("id")
    val kitsuId: String? = null,
    val type: String? = null,
    val videos: List<EpisodeVideo>? = null,
)

@Serializable
data class EpisodeVideo(
    @SerialName("id")
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val title: String? = null,
)

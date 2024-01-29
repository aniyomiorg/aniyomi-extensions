package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

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
    @SerialName("pageInfo")
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
    @SerialName("pageInfo")
    val pageInfo: AnilistPageInfo? = null,
    val airingSchedules: List<AnilistAiringSchedule>? = null, // Change this line
)

@Serializable
data class AnilistAiringSchedule(
    val media: AnilistMedia? = null,
)

@Serializable
data class AnilistPageInfo(
    @SerialName("currentPage")
    val currentPage: Int = 0,

    @SerialName("hasNextPage")
    val hasNextPage: Boolean = false,
)

@Serializable
data class AnilistMedia(
    val id: Int? = null,
    @SerialName("siteUrl")
    val siteUrl: String? = null,
    val title: AnilistTitle? = null,
    @SerialName("coverImage")
    val coverImage: AnilistCoverImage? = null,
    val description: String? = null,
    val status: String? = null,
    val tags: List<AnilistTag>? = null,
    val genres: List<String>? = null,
    val studios: AnilistStudios? = null,
)

@Serializable
data class AnilistTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AnilistCoverImage(
    @SerialName("extraLarge")
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
    @SerialName("meta")
    val meta: EpisodeMeta? = null,
)

@Serializable
data class EpisodeMeta(
    @SerialName("id")
    val kitsuId: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("videos")
    val videos: List<EpisodeVideo>? = null,
)

@Serializable
data class EpisodeVideo(
    @SerialName("id")
    val videoId: String? = null,
    @SerialName("season")
    val season: Int? = null,
    @SerialName("episode")
    val episode: Int? = null,
    @SerialName("released")
    val released: String? = null,
    @SerialName("title")
    val title: String? = null,
)

package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetPopularTitlesResponse(
    val data: PopularTitlesData? = null,
)

@Serializable
data class PopularTitlesData(
    val popularTitles: PopularTitles? = null,
)

@Serializable
data class PopularTitles(
    val edges: List<PopularTitlesEdge>? = null,
    val pageInfo: PageInfo? = null,
)

@Serializable
data class PopularTitlesEdge(
    val node: PopularTitleNode? = null,
)

@Serializable
data class PopularTitleNode(
    val id: String? = null,
    val objectType: String? = null,
    val content: Content? = null,
)

@Serializable
data class Content(
    val fullPath: String? = null,
    val title: String? = null,
    val shortDescription: String? = null,
    val externalIds: ExternalIds? = null,
    val posterUrl: String? = null,
    val genres: List<Genre>? = null,
    val credits: List<Credit>? = null,
)

@Serializable
data class ExternalIds(
    val imdbId: String? = null,
)

@Serializable
data class Genre(
    val translation: String? = null,
)

@Serializable
data class Credit(
    val name: String? = null,
    val role: String? = null,
)

@Serializable
data class PageInfo(
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
)

@Serializable
data class GetUrlTitleDetailsResponse(
    val data: UrlV2Data? = null,
)

@Serializable
data class UrlV2Data(
    val urlV2: UrlV2? = null,
)

@Serializable
data class UrlV2(
    val node: PopularTitleNode? = null,
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
    val id: String? = null,
    val type: String? = null,
    val videos: List<EpisodeVideo>? = null,
)

@Serializable
data class EpisodeVideo(
    val id: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val firstAired: String? = null,
    val name: String? = null,
)

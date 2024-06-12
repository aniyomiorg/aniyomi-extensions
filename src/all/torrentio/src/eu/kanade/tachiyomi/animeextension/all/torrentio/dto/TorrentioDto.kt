package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import kotlinx.serialization.Serializable

@Serializable
class GetPopularTitlesResponse(
    val data: PopularTitlesData? = null,
)

@Serializable
class PopularTitlesData(
    val popularTitles: PopularTitles? = null,
)

@Serializable
class PopularTitles(
    val edges: List<PopularTitlesEdge>? = null,
    val pageInfo: PageInfo? = null,
)

@Serializable
class PopularTitlesEdge(
    val node: PopularTitleNode? = null,
)

@Serializable
class PopularTitleNode(
    val objectType: String? = null,
    val content: Content? = null,
)

@Serializable
class Content(
    val fullPath: String? = null,
    val title: String? = null,
    val shortDescription: String? = null,
    val externalIds: ExternalIds? = null,
    val posterUrl: String? = null,
    val genres: List<Genre>? = null,
    val credits: List<Credit>? = null,
)

@Serializable
class ExternalIds(
    val imdbId: String? = null,
)

@Serializable
class Genre(
    val translation: String? = null,
)

@Serializable
class Credit(
    val name: String? = null,
    val role: String? = null,
)

@Serializable
class PageInfo(
    val hasNextPage: Boolean = false,
)

@Serializable
class GetUrlTitleDetailsResponse(
    val data: UrlV2Data? = null,
)

@Serializable
class UrlV2Data(
    val urlV2: UrlV2? = null,
)

@Serializable
class UrlV2(
    val node: PopularTitleNode? = null,
)

// Stream Data For Torrent
@Serializable
class StreamDataTorrent(
    val streams: List<TorrentioStream>? = null,
)

@Serializable
class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
)

// Episode Data

@Serializable
class EpisodeList(
    val meta: EpisodeMeta? = null,
)

@Serializable
class EpisodeMeta(
    val id: String? = null,
    val type: String? = null,
    val videos: List<EpisodeVideo>? = null,
)

@Serializable
class EpisodeVideo(
    val id: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val firstAired: String? = null,
    val name: String? = null,
)

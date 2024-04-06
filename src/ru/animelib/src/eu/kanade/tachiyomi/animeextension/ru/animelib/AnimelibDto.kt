package eu.kanade.tachiyomi.animeextension.ru.animelib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeStatus(
    val id: Int,
)

@Serializable
data class CoverInfo(
    val thumbnail: String,
)

@Serializable
data class GenreInfo(
    val id: Int,
    val name: String,
)

@Serializable
data class PublisherInfo(
    val id: Int,
    val name: String,
)

@Serializable
data class AuthorInfo(
    val id: Int,
    val name: String,
)

@Serializable
data class AnimeData(
    val id: Int,
    @SerialName("rus_name") val rusName: String,
    @SerialName("slug_url") val href: String,
    @SerialName("status") val animeStatus: AnimeStatus,
    val cover: CoverInfo,

    // Optional
    @SerialName("is_licensed") val licensed: Boolean? = null,
    val summary: String? = null,
    val genres: List<GenreInfo>? = null,
    val publisher: List<PublisherInfo>? = null,
    val authors: List<AuthorInfo>? = null,
)

@Serializable
data class PageMetaData(
    val next: String? = null,
)

@Serializable
data class AnimeList(
    val data: List<AnimeData>,
    val links: PageMetaData? = null,
)

@Serializable
data class AnimeInfo(
    val data: AnimeData,
)

// ============================== Episode ==============================
@Serializable
data class TeamInfo(
    val id: Int,
    val name: String,
)

@Serializable
data class VideoQuality(
    val href: String,
    val quality: Int,
)

@Serializable
data class VideoMetaData(
    val id: Int,
    val quality: List<VideoQuality>,
)

@Serializable
data class TranslationInfo(
    val id: Int,
)

@Serializable
data class SubtitleInfo(
    val id: Int,
    val format: String,
    val src: String,
)

@Serializable
data class VideoInfo(
    val id: Int,
    val player: String,
    val team: TeamInfo,

    @SerialName("translation_type") val translationInfo: TranslationInfo,

    // Kodik player
    val src: String? = null,

    // Animelib player
    val video: VideoMetaData? = null,
    val subtitles: List<SubtitleInfo>? = null,
)

@Serializable
data class EpisodeInfo(
    val id: Int,
    @SerialName("name") val episodeName: String,
    val number: String,
    val season: String,
    @SerialName("created_at") val date: String,

    // Optional
    val players: List<VideoInfo>? = null,
)

@Serializable
data class EpisodeVideoData(
    val data: EpisodeInfo,
)

@Serializable
data class EpisodeList(
    val data: List<EpisodeInfo>,
)

// ============================== VideoServer ==============================
@Serializable
data class VideoServerInfo(
    val id: String,
    val label: String,
    val url: String,
)

@Serializable
data class VideoServers(
    val videoServers: List<VideoServerInfo>,
)

@Serializable
data class VideoServerData(
    val data: VideoServers,
)

// ============================== Kodik ==============================
@Serializable
data class KodikForm(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
data class KodikVideoInfo(
    val src: String,
)

@Serializable
data class KodikVideoQuality(
    @SerialName("480") val bad: List<KodikVideoInfo>,
    @SerialName("720") val good: List<KodikVideoInfo>,
    @SerialName("360") val ugly: List<KodikVideoInfo>,
)

@Serializable
data class KodikData(
    val links: KodikVideoQuality,
)

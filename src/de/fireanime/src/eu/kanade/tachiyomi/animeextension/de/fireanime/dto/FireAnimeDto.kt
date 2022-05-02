package eu.kanade.tachiyomi.animeextension.de.fireanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

abstract class AbsAnimeBaseDto {
    abstract val url: String
    abstract val title: String

    abstract val season: Int?
    abstract val part: Int?

    abstract val imgPoster: String
    abstract val imgWallpaper: String?
}

@Serializable
class AnimeBaseDto(
    override val url: String,
    override val title: String,

    override val season: Int?,
    override val part: Int?,

    override val imgPoster: String,
    override val imgWallpaper: String?,
) : AbsAnimeBaseDto()

@Serializable
data class AnimeDto(
    val id: Int,
    override val url: String,
    override val title: String,

    override val season: Int?,
    override val part: Int?,
    val episodes: Int,

    override val imgPoster: String,
    override val imgWallpaper: String?,
) : AbsAnimeBaseDto()

@Serializable
data class AnimeDetailsWrapperDto(
    val response: AnimeDetailsDto
)

@Serializable
data class AnimeDetailsDto(
    val title: String,
    val description: String,

    val season: Int?,
    val part: Int?,

    val imgPoster: String,
    val imgWallpaper: String?,

    @SerialName("generes")
    val genres: List<GenreDto>,
    val tags: List<TagDto>,

    val fsk: Int,
    @SerialName("voting")
    val votingStr: String?,
    val votingDouble: Double? = votingStr?.toDoubleOrNull(),
)

@Serializable
data class AiringEpisodeDto(
    override val url: String,
    override val title: String,

    val info: String,
    val status: String,

    override val season: Int?,
    override val part: Int?,
    val episode: Int,

    val langId: Int,
    val isSub: Int,

    @SerialName("time")
    val timeSeconds: Long,
    val timeMillis: Long = timeSeconds * 1000,

    override val imgPoster: String,
    override val imgWallpaper: String?,
    val imgThumb: String,
) : AbsAnimeBaseDto()

@Serializable
data class EpisodeListingWrapperDto(
    val response: List<EpisodeListingDto>
)

@Serializable
data class EpisodeListingDto(
    val title: String,
    val episode: Int,
    val img: String,
    val version: String,
)

@Serializable
data class EpisodeSourcesDto(
    val id: Int,
    val title: String,
    val episode: Int,
    val img: String,
    @SerialName("links")
    val hosters: List<HosterSourceDto>,
    val cdns: List<CdnSourceDto>,
)

@Serializable
data class VideoLinkDto(
    @SerialName("response")
    val url: String
)

abstract class AbsSourceBaseDto {
    abstract val id: Int
    abstract val isSub: Int
}

@Serializable
data class HosterSourceDto(
    override val id: Int,
    override val isSub: Int,
    val hoster: String,
) : AbsSourceBaseDto()

@Serializable
data class CdnSourceDto(
    override val id: Int,
    override val isSub: Int,
) : AbsSourceBaseDto()

@Serializable
data class GenreDto(
    val id: Int,
    @SerialName("genere")
    val genre: String,
)

@Serializable
data class TagDto(
    val id: Int,
    val name: String,
    val description: String,
    val category: String,
    val isAdult: Int,
)

@Serializable
data class FireCdnFileDto(
    val proxy: String,
    val file: String,
)

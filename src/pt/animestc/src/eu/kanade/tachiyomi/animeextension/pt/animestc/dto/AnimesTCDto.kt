package eu.kanade.tachiyomi.animeextension.pt.animestc.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponseDto<T>(
    @SerialName("data")
    val items: List<T>,
    val lastPage: Int,
    val page: Int,
)

@Serializable
data class AnimeDto(
    val classification: String?,
    val cover: CoverDto,
    val id: Int,
    val producer: String?,
    val releaseStatus: String,
    val synopsis: String,
    val tags: List<TagDto>,
    val title: String,
    val year: Int?,
) {
    val status by lazy {
        when (releaseStatus) {
            "complete" -> SAnime.COMPLETED
            "airing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    val genres by lazy { tags.joinToString(", ") { it.name } }

    @Serializable
    data class TagDto(val name: String)
}

@Serializable
data class EpisodeDto(
    @SerialName("seriesId")
    val animeId: Int,
    val cover: CoverDto?,
    val created_at: String,
    val number: String,
    val slug: String,
    val title: String,
)

@Serializable
data class VideoDto(
    val id: Int,
    val links: VideoLinksDto,
) {
    @Serializable
    data class VideoLinksDto(
        val low: List<VideoLink> = emptyList(),
        val medium: List<VideoLink> = emptyList(),
        val high: List<VideoLink> = emptyList(),
        val online: List<String>? = null,
    )

    @Serializable
    data class VideoLink(
        val index: Int,
        val name: String,
        val quality: String,
    )
}

@Serializable
data class CoverDto(
    val originalName: String,
) {
    val url by lazy { "https://stc.animestc.com/$originalName" }
}

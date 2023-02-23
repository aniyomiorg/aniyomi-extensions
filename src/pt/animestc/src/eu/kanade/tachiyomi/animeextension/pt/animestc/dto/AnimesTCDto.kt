package eu.kanade.tachiyomi.animeextension.pt.animestc.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponseDto<T>(
    val page: Int,
    val lastPage: Int,
    @SerialName("data")
    val items: List<T>
)

@Serializable
data class EpisodeDto(
    @SerialName("id")
    val episodeId: Int,
    @SerialName("seriesId")
    val animeId: Int,
    val number: String,
    val title: String,
    val cover: CoverDto,
    val links: VideoLinksDto
) {
    @Serializable
    data class VideoLinksDto(
        val low: List<VideoLink> = emptyList(),
        val medium: List<VideoLink> = emptyList(),
        val high: List<VideoLink> = emptyList(),
        val online: List<String>? = null
    )

    @Serializable
    data class VideoLink(
        val name: String,
        val index: Int,
        val quality: String
    )
}

@Serializable
data class CoverDto(
    val originalName: String
) {
    val url by lazy { "https://stc.animestc.com/$originalName" }
}

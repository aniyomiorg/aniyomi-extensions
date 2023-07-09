package eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResultsDto(val contentItem: ContentDto) {
    val items by lazy { contentItem.films + contentItem.series }
}

@Serializable
data class ContentDto(
    val films: List<EntryDto>,
    val series: List<EntryDto>,
)

@Serializable
data class EntryDto(
    val urlPath: String,
    val title: String,
    val image: List<ImageDto>,
) {
    @Serializable
    data class ImageDto(val path: String)

    val thumbnailPath by lazy { image.first().path }
}

@Serializable
data class SerieEpisodesDto(
    @SerialName("Saison")
    val seasons: Map<String, List<EpisodeDto>>,
)

@Serializable
data class EpisodeDto(
    val episode: Int = 1,
    @SerialName("saison")
    val season: Int = 1,
    val title: String,
    val createdAt: DateDto,
    val video: List<VideoDto>,
) {
    val date by lazy { createdAt.date.substringBefore(" ") }
}

@Serializable
data class DateDto(val date: String)

@Serializable
data class VideoDto(val id: Int, val property: String, val version: String) {
    val encoded by lazy { "$id|$version|$property" }
}

@Serializable
data class MovieInfoDto(
    @SerialName("Titre")
    val title: String,
    @SerialName("CreatedAt")
    val createdAt: DateDto,
    @SerialName("Iframe")
    val videos: List<VideoDto>,
) {
    val date by lazy { createdAt.date.substringBefore(" ") }
}

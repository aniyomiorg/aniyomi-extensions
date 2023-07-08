package eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto

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

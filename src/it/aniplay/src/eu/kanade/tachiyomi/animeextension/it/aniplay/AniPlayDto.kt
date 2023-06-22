package eu.kanade.tachiyomi.animeextension.it.aniplay

import kotlinx.serialization.Serializable

@Serializable
data class VideoResult(
    val id: Int,
    val videoUrl: String,
)

@Serializable
data class SearchResult(
    val id: Int,
    val title: String,
    val storyline: String,
    val verticalImages: List<Image>,
) {
    @Serializable
    data class Image(
        val imageFull: String,
    )
}

@Serializable
data class AnimeResult(
    val id: Int,
    val title: String,
    val startDate: String? = null,
    val storyline: String,
    val type: String,
    val origin: String,
    val status: String,
    val studio: String,
    val verticalImages: List<Image>,
    val seasons: List<Season>,
    val episodes: List<Episode>,
) {
    @Serializable
    data class Season(
        val id: Int,
    )

    @Serializable
    data class Episode(
        val id: Int,
        val episodeNumber: String,
        val title: String? = null,
        val airingDate: String? = null,
    )

    @Serializable
    data class Image(
        val imageFull: String,
    )
}

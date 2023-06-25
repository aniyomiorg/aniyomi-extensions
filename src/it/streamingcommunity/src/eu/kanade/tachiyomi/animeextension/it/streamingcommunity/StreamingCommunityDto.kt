package eu.kanade.tachiyomi.animeextension.it.streamingcommunity

import kotlinx.serialization.Serializable

@Serializable
data class ShowsResponse(
    val props: PropObject,
)

@Serializable
data class PropObject(
    val titles: List<TitleObject>,
) {
    @Serializable
    data class TitleObject(
        val id: Int,
        val slug: String,
        val name: String,
        val images: List<ImageObject>,
    ) {
        @Serializable
        data class ImageObject(
            val filename: String,
            val type: String,
        )
    }
}

@Serializable
data class SingleShowResponse(
    val props: SingleShowObject,
    val version: String? = null,
) {
    @Serializable
    data class SingleShowObject(
        val title: ShowObject? = null,
        val loadedSeason: LoadedSeasonObject? = null,
    ) {
        @Serializable
        data class ShowObject(
            val id: Int,
            val plot: String? = null,
            val status: String? = null,
            val seasons: List<SeasonObject>,
            val genres: List<GenreObject>? = null,
        ) {
            @Serializable
            data class SeasonObject(
                val id: Int,
                val number: Int,
            )

            @Serializable
            data class GenreObject(
                val name: String,
            )
        }

        @Serializable
        data class LoadedSeasonObject(
            val id: Int,
            val episodes: List<EpisodeObject>,
        ) {
            @Serializable
            data class EpisodeObject(
                val id: Int,
                val number: Int,
                val name: String,
            )
        }
    }
}

@Serializable
data class SearchAPIResponse(
    val data: List<PropObject.TitleObject>,
)

@Serializable
data class GenreAPIResponse(
    val titles: List<PropObject.TitleObject>,
)

@Serializable
data class VideoResponse(
    val props: VideoPropObject,
) {
    @Serializable
    data class VideoPropObject(
        val embedUrl: String,
    )
}

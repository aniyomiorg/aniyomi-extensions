package eu.kanade.tachiyomi.animeextension.all.consumybili

import kotlinx.serialization.Serializable

@Serializable
data class AnilistResponse(
    val data: DataObject,
) {
    @Serializable
    data class DataObject(
        val Page: PageObject,
    ) {
        @Serializable
        data class PageObject(
            val pageInfo: PageInfoObject,
            val media: List<AnimeMedia>,
        ) {
            @Serializable
            data class PageInfoObject(
                val hasNextPage: Boolean,
            )

            @Serializable
            data class AnimeMedia(
                val id: Int,
                val title: TitleObject,
                val coverImage: ImageObject,
                val studios: StudioNode,
                val genres: List<String>,
                val description: String,
                val status: String,
            ) {
                @Serializable
                data class TitleObject(
                    val romaji: String,
                )

                @Serializable
                data class ImageObject(
                    val extraLarge: String,
                )

                @Serializable
                data class StudioNode(
                    val nodes: List<Node>,
                ) {
                    @Serializable
                    data class Node(
                        val name: String,
                    )
                }
            }
        }
    }
}

@Serializable
data class EpisodeResponse(
    val episodes: List<EpisodeObject>? = null,
) {
    @Serializable
    data class EpisodeObject(
        val sourceEpisodeId: String,
        val sourceMediaId: String,
        val sourceId: String,
        val episodeNumber: Float,
    )
}

@Serializable
data class SourcesResponse(
    val sources: List<SourceObject>,
    val subtitles: List<SubtitleObject>,
) {
    @Serializable
    data class SourceObject(
        val file: String,
        val type: String,
    )

    @Serializable
    data class SubtitleObject(
        val file: String,
        val lang: String,
        val language: String,
    )
}

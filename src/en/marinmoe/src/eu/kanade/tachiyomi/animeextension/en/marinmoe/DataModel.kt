package eu.kanade.tachiyomi.animeextension.en.marinmoe

import kotlinx.serialization.Serializable

@Serializable
data class ResponseData(
    val props: PropData,
) {
    @Serializable
    data class PropData(
        val anime_list: AnimeList,
    ) {
        @Serializable
        data class AnimeList(
            val data: List<Anime>,
            val meta: MetaData,
        ) {
            @Serializable
            data class Anime(
                val title: String,
                val slug: String,
                val cover: String,
            )

            @Serializable
            data class MetaData(
                val current_page: Int,
                val last_page: Int,
            )
        }
    }
}

@Serializable
data class AnimeDetails(
    val props: DetailsData,
) {
    @Serializable
    data class DetailsData(
        val anime: AnimeDetailsData,
        val episode_list: EpisodesData,
    ) {
        @Serializable
        data class AnimeDetailsData(
            val title: String,
            val cover: String,
            val type: InfoType,
            val status: InfoType,
            val content_rating: InfoType,
            val release_date: String,
            val description: String,
            val genre_list: List<InfoData>,
            val production_list: List<InfoData>,
            val source_list: List<InfoData>,
        ) {
            @Serializable
            data class InfoType(
                val id: Int,
                val name: String,
            )

            @Serializable
            data class InfoData(
                val name: String,
            )
        }

        @Serializable
        data class EpisodesData(
            val data: List<EpisodeData>,
            val links: LinksData,
        ) {
            @Serializable
            data class EpisodeData(
                val title: String,
                val sort: Float,
                val slug: String,
                val type: Int,
                val release_date: String,
            )

            @Serializable
            data class LinksData(
                val next: String? = null,
            )
        }
    }
}

@Serializable
data class EpisodeData(
    val props: PropData,
) {
    @Serializable
    data class PropData(
        val video_list: VideoList,
    ) {
        @Serializable
        data class VideoList(
            val data: List<Video>,
        ) {
            @Serializable
            data class Video(
                val slug: String,
            )
        }
    }
}

@Serializable
data class EpisodeResponse(
    val props: PropData,
) {
    @Serializable
    data class PropData(
        val video: VideoData,
    ) {
        @Serializable
        data class VideoData(
            val data: Video,
        ) {
            @Serializable
            data class Video(
                val title: String,
                val sort: Float,
                val audio: TrackInfo,
                val source: SourceInfo,
                val mirror: List<Track>,
            ) {
                @Serializable
                data class TrackInfo(
                    val code: String,
                )

                @Serializable
                data class SourceInfo(
                    val name: String,
                )

                @Serializable
                data class Track(
                    val resolution: String,
                    val code: TrackCode,
                ) {
                    @Serializable
                    data class TrackCode(
                        val file: String,
                    )
                }
            }
        }
    }
}

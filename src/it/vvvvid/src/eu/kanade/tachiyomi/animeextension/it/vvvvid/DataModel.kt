package eu.kanade.tachiyomi.animeextension.it.vvvvid

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    val data: LoginData,
) {
    @Serializable
    data class LoginData(
        val conn_id: String,
        val sessionId: String,
    )
}

@Serializable
data class AnimesResponse(
    val data: List<AnimeData>,
) {
    @Serializable
    data class AnimeData(
        val id: Int,
        val show_id: Int,
        val title: String,
        val thumbnail: String,
    )
}

@Serializable
data class SeasonsResponse(
    val data: List<SeasonObject>,
) {
    @Serializable
    data class SeasonObject(
        val name: String,
        val show_id: Int,
        val episodes: List<EpisodeObject>,
    ) {
        @Serializable
        data class EpisodeObject(
            val id: Int,
            val season_id: Int,
            val video_id: Int,
            val number: String,
            val title: String,
        )
    }
}

@Serializable
data class InfoResponse(
    val data: InfoObject,
) {
    @Serializable
    data class InfoObject(
        val title: String,
        val thumbnail: String,
        val description: String,
        val date_published: String,
        val additional_info: String,
        val show_genres: List<String>? = null,
    )
}

@Serializable
data class ChannelsResponse(
    val data: List<ChannelsObject>,
) {
    @Serializable
    data class ChannelsObject(
        val id: Int,
        val name: String,
        val category: List<Category>? = null,
        val filter: List<String>? = null,
    ) {
        @Serializable
        data class Category(
            val name: String,
            val id: Int,
        )
    }
}

@Serializable
data class VideosResponse(
    val data: List<VideoObject>,
) {
    @Serializable
    data class VideoObject(
        val video_id: Int,
        val embed_info: String,
        val embed_info_sd: String? = null,
    )
}

@Serializable
data class LinkData(
    val show_id: Int,
    val season_id: Int,
    val video_id: Int,
)

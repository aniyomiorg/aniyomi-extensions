package eu.kanade.tachiyomi.animeextension.de.moflixstream.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class PopularPaginationDto(val pagination: PopularData) {
    @Serializable
    data class PopularData(val data: List<ItemInfo>, val next_page: Int? = null, val current_page: Int)
}

@Serializable
data class ItemInfo(
    val poster: String?,
    val backdrop: String?,
    val description: String = "",
    @Serializable(with = StringSerializer::class)
    val id: String,
    val name: String,
    val genres: List<GenreDto> = emptyList(),
) {
    val thumbnail by lazy { poster ?: backdrop }
}

@Serializable
data class GenreDto(@SerialName("display_name") val name: String)

@Serializable
data class SearchDto(val results: List<ItemInfo> = emptyList())

@Serializable
data class AnimeDetailsDto(val title: ItemInfo)

@Serializable
data class EpisodePageDto(
    val title: SimpleItemDto,
    val seasons: SeasonListDto? = null,
) {
    @Serializable
    data class SimpleItemDto(
        @Serializable(with = StringSerializer::class)
        val id: String,
    )
}

@Serializable
data class SeasonListDto(
    val data: List<SeasonDto> = emptyList(),
    val next_page: Int? = null,
)

@Serializable
data class SeasonPaginationDto(val pagination: SeasonListDto)

@Serializable
data class SeasonDto(val number: Int)

@Serializable
data class EpisodeListDto(val episodes: EpisodesDataDto) {
    @Serializable
    data class EpisodesDataDto(val data: List<EpisodeDto>)

    @Serializable
    data class EpisodeDto(val name: String, val episode_number: Int)
}

@Serializable
data class VideoResponseDto(
    val episode: VideoListDto? = null,
    val title: VideoListDto? = null,
)

@Serializable
data class VideoListDto(val videos: List<VideoDto> = emptyList())

@Serializable
data class VideoDto(val name: String, val src: String)

object StringSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement) =
        when (element) {
            is JsonPrimitive -> JsonPrimitive(element.content)
            else -> JsonPrimitive("")
        }
}

package eu.kanade.tachiyomi.animeextension.all.animeonsen.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class AnimeListResponse(
    val content: List<AnimeListItem>,
    val cursor: AnimeListCursor
)

@Serializable
data class AnimeListItem(
    val content_id: String,
    val content_title: String? = null,
    val content_title_en: String? = null,
)

@Serializable
data class AnimeListCursor(
    val next: JsonArray
)

@Serializable
data class AnimeDetails(
    val content_id: String,
    val content_title: String?,
    val content_title_en: String?,
    val mal_data: MalData?,
)

@Serializable
data class MalData(
    val genres: List<Genre>?,
    val status: String?,
    val studios: List<Studio>?,
    val synopsis: String?,
)

@Serializable
data class Genre(
    val name: String,
)

@Serializable
data class Studio(
    val name: String,
)

@Serializable
data class VideoData(
    val metadata: MetaData,
    val uri: StreamData,
)

@Serializable
data class MetaData(
    val subtitles: JsonObject,
)

@Serializable
data class StreamData(
    val stream: String,
    val subtitles: JsonObject,
)

@Serializable
data class SearchResponse(
    val status: Int,
    val result: List<AnimeListItem>,
)

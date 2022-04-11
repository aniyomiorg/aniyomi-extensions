package eu.kanade.tachiyomi.animeextension.ru.animevost.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeDetailsDto(
    @SerialName("data")
    val data: List<Data>? = null,
)

@Serializable
data class Data(
    @SerialName("description")
    val description: String? = null,
    @SerialName("director")
    val director: String? = null,
    @SerialName("urlImagePreview")
    val preview: String? = null,
    @SerialName("year")
    val year: String? = null,
    @SerialName("genre")
    val genre: String? = null,
    @SerialName("id")
    val id: Int? = null,
    @SerialName("timer")
    val timer: Int? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("series")
    val series: String? = null,
)

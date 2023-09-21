package eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchDto(
    val searchResult: List<SearchResult>? = emptyList(),
)

@Serializable
data class SearchResult(
    val id: String,
    @SerialName("t") val title: String,
)

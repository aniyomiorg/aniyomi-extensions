package eu.kanade.tachiyomi.animeextension.tr.animeler.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponseDto(
    val data: List<SimpleAnimeDto>,
    val pages: Int,
)

@Serializable
data class SimpleAnimeDto(
    val url: String,
    val image: String,
    val post: PostDto,
) {
    @Serializable
    data class PostDto(val post_title: String)
    val title = post.post_title
}

@Serializable
data class TaxonomyDto(val taxonomy: String = "", val terms: List<Int> = emptyList())

@Serializable
data class SearchRequestDto(
    val single: SingleDto,
    val keyword: String = "",
    val query: String = "",
    val tax: List<TaxonomyDto>,
)

@Serializable
data class SingleDto(
    val paged: Int,
    @SerialName("meta_key")
    val key: String?,
    val order: String,
    val orderBy: String,
    val season: String?,
    val year: String?,
)

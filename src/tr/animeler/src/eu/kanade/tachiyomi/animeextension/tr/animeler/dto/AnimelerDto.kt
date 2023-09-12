package eu.kanade.tachiyomi.animeextension.tr.animeler.dto

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

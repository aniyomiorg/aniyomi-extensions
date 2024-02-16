package eu.kanade.tachiyomi.animeextension.tr.animeler.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SearchResponseDto(val data: String, val pages: Int)

@Serializable
data class PostDto(
    val post_title: String,
    val post_content: String? = null,
)

@Serializable
data class ThumbnailDto(private val featured_url: JsonPrimitive) {
    val url = if (featured_url.isString) featured_url.content else null
}

@Serializable
data class TaxonomyDto(val taxonomy: String = "", val terms: List<Int> = emptyList())

@Serializable
data class SearchRequestDto(
    val single: SingleDto,
    val keyword: String,
    val query: String,
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

@Serializable
data class FullAnimeDto(
    val url: String,
    val post: PostDto,
    val meta: MetaDto,
    private val taxonomies: TaxonomiesDto,
    private val image: String = "",
    private val images: ThumbnailDto? = null,
) {
    val thumbnail = image.ifEmpty { images?.url }
    val title = post.post_title

    @Serializable
    data class MetaDto(
        val native: String? = null,
        val synonyms: String? = null,
        val score: String? = null,
        val premiered: String? = null,
        val aired: String? = null,
        val duration: String? = null,
        val rate: String? = null,
    )

    @Serializable
    data class TaxonomiesDto(
        val producer: List<ItemDto> = emptyList(),
        val studio: List<ItemDto> = emptyList(),
        val genre: List<ItemDto> = emptyList(),
    )

    val genres = taxonomies.genre.parseItems()
    val studios = taxonomies.studio.parseItems()
    val producers = taxonomies.producer.parseItems()
}

@Serializable
data class ItemDto(val name: String)

private fun List<ItemDto>.parseItems() = joinToString { it.name }.takeIf(String::isNotBlank)

@Serializable
data class AnimeEpisodes(val episodes: List<EpisodeDto>)

@Serializable
data class EpisodeDto(
    val url: String,
    val post: EpisodePostDto,
    val meta: EpisodeMetaDto,
) {
    @Serializable
    data class EpisodeMetaDto(val number: String)

    @Serializable
    data class EpisodePostDto(val post_modified_gmt: String? = null)

    val date = post.post_modified_gmt ?: ""
}

@Serializable
data class SourcesDto(val sourceList: Map<String, String>)

@Serializable
data class VideoDto(val videoSrc: String)

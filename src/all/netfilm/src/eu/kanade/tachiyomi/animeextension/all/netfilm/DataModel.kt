package eu.kanade.tachiyomi.animeextension.all.netfilm

import kotlinx.serialization.Serializable

@Serializable
data class CategoryResponse(
    val data: List<CategoryData>,
) {
    @Serializable
    data class CategoryData(
        val coverVerticalUrl: String,
        val domainType: Int,
        val id: String,
        val name: String,
        val sort: String,
    )
}

@Serializable
data class AnimeInfoResponse(
    val data: InfoData,
) {
    @Serializable
    data class InfoData(
        val coverVerticalUrl: String,
        val episodeVo: List<EpisodeInfo>,
        val id: String,
        val introduction: String,
        val name: String,
        val category: Int,
        val tagList: List<IdInfo>,
    ) {
        @Serializable
        data class EpisodeInfo(
            val id: Int,
            val seriesNo: Float,
        )

        @Serializable
        data class IdInfo(
            val name: String,
        )
    }
}

@Serializable
data class SearchResponse(
    val data: InfoData,
) {
    @Serializable
    data class InfoData(
        val results: List<CategoryResponse.CategoryData>,
    )
}

@Serializable
data class EpisodeResponse(
    val data: EpisodeData,
) {
    @Serializable
    data class EpisodeData(
        val qualities: List<Quality>,
        val subtitles: List<Subtitle>,
    ) {
        @Serializable
        data class Quality(
            val quality: Int,
            val url: String,
        )

        @Serializable
        data class Subtitle(
            val language: String,
            val url: String,
        )
    }
}

@Serializable
data class LinkData(
    val category: String,
    val id: String,
    val url: String,
    val episodeId: String? = null,
)

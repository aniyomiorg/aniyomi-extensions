package eu.kanade.tachiyomi.animeextension.it.animeunity

import kotlinx.serialization.Serializable

@Serializable
data class AnimeResponse(
    val current_page: Int,
    val last_page: Int,
    val data: List<Anime>,
) {
    @Serializable
    data class Anime(
        val id: Int,
        val slug: String,
        val title_eng: String,
        val imageurl: String? = null,
    )
}

@Serializable
data class Episode(
    val number: String,
    val created_at: String,
    val id: Int? = null,
)

@Serializable
data class ApiResponse(
    val episodes: List<Episode>,
)

@Serializable
data class ServerResponse(
    val name: String,
    val client_ip: String,
    val folder_id: String,
    val proxy_download: Int,
    val storage_download: StorageDownload,
) {
    @Serializable
    data class StorageDownload(
        val number: Int,
    )
}

@Serializable
data class LinkData(
    val id: String,
    val file_name: String,
)

@Serializable
data class AnimeInfo(
    val title_eng: String,
    val imageurl: String,
    val plot: String,
    val date: String,
    val season: String,
    val slug: String,
    val id: Int,
    val type: String,
    val status: String,
    val genres: List<Genre>,
    val studio: String? = null,
    val score: String? = null,
) {
    @Serializable
    data class Genre(
        val name: String,
    )
}

@Serializable
data class SearchResponse(
    val records: List<AnimeInfo>,
    val tot: Int,
)

package eu.kanade.tachiyomi.animeextension.all.googledrive

import kotlinx.serialization.Serializable

@Serializable
data class PostResponse(
    val nextPageToken: String? = null,
    val items: List<ResponseItem>? = null,
) {
    @Serializable
    data class ResponseItem(
        val id: String,
        val title: String,
        val mimeType: String,
        val fileSize: String? = null,
    )
}

@Serializable
data class LinkData(
    val url: String,
    val type: String,
    val info: LinkDataInfo? = null,
)

@Serializable
data class LinkDataInfo(
    val title: String,
    val size: String,
)

@Serializable
data class DownloadResponse(
    val downloadUrl: String,
)

@Serializable
data class DetailsJson(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: String? = null,
)

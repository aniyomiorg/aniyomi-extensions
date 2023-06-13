package eu.kanade.tachiyomi.animeextension.all.googledriveindex

import kotlinx.serialization.Serializable

@Serializable
data class ResponseData(
    val nextPageToken: String? = null,
    val data: DataObject,
) {
    @Serializable
    data class DataObject(
        val files: List<FileObject>,
    ) {
        @Serializable
        data class FileObject(
            val mimeType: String,
            val id: String,
            val name: String,
            val modifiedTime: String? = null,
            val size: String? = null,
        )
    }
}

@Serializable
data class LinkData(
    val type: String,
    val url: String,
    val info: String? = null,
    val fragment: String? = null,
)

@Serializable
data class IdUrl(
    val id: String,
    val url: String,
    val referer: String,
    val type: String,
)

@Serializable
data class Details(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: String? = null,
)

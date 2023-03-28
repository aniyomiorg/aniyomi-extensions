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
)

@Serializable
data class IdUrl(
    val id: String,
    val url: String,
    val referer: String,
    val type: String,
)

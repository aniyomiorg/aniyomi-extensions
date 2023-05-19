package eu.kanade.tachiyomi.animeextension.all.jellyfin

import kotlinx.serialization.Serializable

@Serializable
data class ItemsResponse(
    val TotalRecordCount: Int,
    val Items: List<Item>,
) {
    @Serializable
    data class Item(
        val Name: String,
        val Id: String,
        val Type: String,
        val LocationType: String,
        val ImageTags: ImageObject,
        val IndexNumber: Float? = null,
        val Genres: List<String>? = null,
        val Status: String? = null,
        val SeriesStudio: String? = null,
        val Overview: String? = null,
        val SeriesName: String? = null,
        val SeriesId: String? = null,
    ) {
        @Serializable
        data class ImageObject(
            val Primary: String? = null,
        )
    }
}

@Serializable
data class SessionResponse(
    val MediaSources: List<MediaObject>,
    val PlaySessionId: String,
) {
    @Serializable
    data class MediaObject(
        val MediaStreams: List<MediaStream>,
    ) {
        @Serializable
        data class MediaStream(
            val Codec: String,
            val Index: Int,
            val Type: String,
            val SupportsExternalStream: Boolean,
            val IsExternal: Boolean,
            val Language: String? = null,
            val DisplayTitle: String? = null,
            val Height: Int? = null,
            val Width: Int? = null,
        )
    }
}

@Serializable
data class LinkData(
    val path: String,
    val seriesId: String,
    val seasonId: String,
)

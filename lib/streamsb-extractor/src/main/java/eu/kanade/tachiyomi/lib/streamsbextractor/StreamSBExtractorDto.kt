package eu.kanade.tachiyomi.lib.streamsbextractor

import kotlinx.serialization.Serializable

@Serializable
data class Response(
    val stream_data: ResponseObject,
) {
    @Serializable
    data class ResponseObject(
        val file: String,
        val subs: List<Subtitle>? = null,
    )
}

@Serializable
data class Subtitle(
    val label: String,
    val file: String,
)

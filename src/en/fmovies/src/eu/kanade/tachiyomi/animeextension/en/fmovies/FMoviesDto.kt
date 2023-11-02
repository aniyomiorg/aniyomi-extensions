package eu.kanade.tachiyomi.animeextension.en.fmovies

import kotlinx.serialization.Serializable

@Serializable
data class VrfResponse(
    val url: String,
    val vrfQuery: String? = null,
)

@Serializable
data class AjaxResponse(
    val result: String,
)

@Serializable
data class AjaxServerResponse(
    val result: UrlObject,
) {
    @Serializable
    data class UrlObject(
        val url: String,
    )
}

@Serializable
data class EpisodeInfo(
    val id: String,
    val url: String,
)

@Serializable
data class FMoviesSubs(
    val file: String,
    val label: String,
)

@Serializable
data class RawResponse(
    val rawURL: String,
)

@Serializable
data class VidsrcResponse(
    val result: ResultObject,
) {
    @Serializable
    data class ResultObject(
        val sources: List<SourceObject>,
    ) {
        @Serializable
        data class SourceObject(
            val file: String,
        )
    }
}

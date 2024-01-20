package eu.kanade.tachiyomi.animeextension.en.fmovies

import kotlinx.serialization.Serializable

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
data class MediaResponseBody(
    val status: Int,
    val result: Result,
) {
    @Serializable
    data class Result(
        val sources: ArrayList<Source>,
        val tracks: ArrayList<SubTrack> = ArrayList(),
    ) {
        @Serializable
        data class Source(
            val file: String,
        )

        @Serializable
        data class SubTrack(
            val file: String,
            val label: String = "",
            val kind: String,
        )
    }
}

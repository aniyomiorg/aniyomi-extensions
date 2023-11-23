package eu.kanade.tachiyomi.animeextension.en.nineanime

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
data class ServerResponse(
    val result: Result,
) {

    @Serializable
    data class Result(
        val url: String,
    )
}

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

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document {
        return Jsoup.parseBodyFragment(result)
    }
}

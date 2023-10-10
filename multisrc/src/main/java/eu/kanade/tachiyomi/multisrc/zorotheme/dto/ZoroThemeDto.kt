package eu.kanade.tachiyomi.multisrc.zorotheme.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
data class HtmlResponse(
    val html: String,
) {
    fun getHtml(): Document {
        return Jsoup.parseBodyFragment(html)
    }
}

@Serializable
data class SourcesResponse(
    val link: String? = null,
)

@Serializable
data class VideoDto(
    val sources: List<VideoLink>,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class SourceResponseDto(
    val sources: JsonElement,
    val encrypted: Boolean = true,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class VideoLink(val file: String = "")

@Serializable
data class TrackDto(val file: String, val kind: String, val label: String = "")

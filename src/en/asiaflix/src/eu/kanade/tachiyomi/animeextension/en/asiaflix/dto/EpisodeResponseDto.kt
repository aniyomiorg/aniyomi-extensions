package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class EpisodeResponseDto(
    val episodes: List<EpisodeDto>,
)

@Serializable
data class EpisodeDto(
    val number: JsonPrimitive,
    val type: String,
    val videoUrl: String,
) {
    val url = getUrlWithoutDomain(videoUrl)
        .replace("/ajax.php", "/streaming.php")

    val sub get() = when {
        type.contains("sub", true) -> "Subbed"
        type.contains("dub", true) -> "Dubbed"
        else -> null
    }

    companion object {
        private fun getUrlWithoutDomain(url: String): String {
            val httpUrl = url.toHttpUrlOrNull()

            val path = httpUrl?.encodedPath

            val queries = httpUrl?.encodedQuery.let {
                if (it.isNullOrEmpty()) {
                    ""
                } else {
                    "?$it"
                }
            }

            val frag = httpUrl?.encodedFragment.let {
                if (it.isNullOrEmpty()) {
                    ""
                } else {
                    "#$it"
                }
            }

            return path + queries + frag
        }
    }
}

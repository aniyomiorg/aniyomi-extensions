package eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto

import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy

@Serializable
data class EpisodesDto(
    val title: String,
    val season: List<Season>? = emptyList(),
    val episodes: List<Episode?>? = emptyList(),
)

@Serializable
data class Season(
    val id: String,
)

@Serializable
data class SeasonEpisodesDto(
    val episodes: List<Episode>? = emptyList(),
)

@Serializable
data class Episode(
    val id: String,
    @SerialName("t") val title: String,
    @SerialName("s") val season: String,
    @SerialName("ep") val episode: String,
    val time: String,
) {
    fun toSEpisode(seriesTitle: String) = SEpisode.create().apply {
        name = "$season $episode: $title"
        url = EpisodeUrl(id, seriesTitle).let(JSON::encodeToString)
        scanlator = time
    }

    companion object {
        private val JSON: Json by injectLazy()
    }
}

@Serializable
data class EpisodeUrl(
    val id: String,
    val title: String,
)

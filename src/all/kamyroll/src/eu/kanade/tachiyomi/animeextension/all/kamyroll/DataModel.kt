package eu.kanade.tachiyomi.animeextension.all.kamyroll

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccessToken(
    val access_token: String,
    val token_type: String,
)

@Serializable
data class LinkData(
    val id: String,
    val type: String
)

@Serializable
data class Images(
    val poster_tall: List<ArrayList<Image>>? = null
) {
    @Serializable
    data class Image(
        val width: Int,
        val height: Int,
        val type: String,
        val source: String
    )
}

@Serializable
data class Anime(
    val id: String,
    val type: String? = null,
    val title: String,
    val description: String,
    val images: Images,
    val series_metadata: Metadata? = null,
    val content_provider: String? = null,
    val audio_locales: ArrayList<String>? = null,
    val subtitle_locales: ArrayList<String>? = null
) {
    @Serializable
    data class Metadata(
        val maturity_ratings: ArrayList<String>,
        val is_simulcast: Boolean,
        val audio_locales: ArrayList<String>,
        @SerialName("tenant_categories")
        val genres: ArrayList<String>?
    )
}

@Serializable
data class AnimeResult(
    val total: Int,
    val data: ArrayList<Anime>
)

@Serializable
data class SearchAnimeResult(
    val total: Int,
    val data: ArrayList<Result>
) {
    @Serializable
    data class Result(
        val type: String,
        val count: Int,
        val items: ArrayList<Anime>
    )
}

@Serializable
data class EpisodeDto(
    val id: String,
    val title: String,
    val season_number: Int,
    val episode_number: Float,
    val releaseDate: String
)

@Serializable
data class RawEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Float,
    val releaseDate: String,
    val audLang: String
)

@Serializable
data class EpisodeData(
    val ids: List<Episode>
) {
    @Serializable
    data class Episode(
        val epId: String,
        val audLang: String
    )
}

@Serializable
data class VideoStreams(
    val sources: List<Stream>,
    val subtitles: List<Subtitle>
) {
    @Serializable
    data class Stream(
        val url: String,
        val quality: String
    )

    @Serializable
    data class Subtitle(
        val url: String,
        val lang: String
    )
}

fun <T> List<T>.thirdLast(): T? {
    if (size < 3) return null
    return this[size - 3]
}

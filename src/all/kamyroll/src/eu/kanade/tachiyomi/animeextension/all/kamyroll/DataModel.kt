package eu.kanade.tachiyomi.animeextension.all.kamyroll

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AccessToken(
    val access_token: String,
    val token_type: String,
)

@Serializable
data class LinkData(
    val id: String,
    val media_type: String
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
        val subtitle_locales: ArrayList<String>,
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
    val results: ArrayList<SearchAnime>,
) {
    @Serializable
    data class SearchAnime(
        val id: String,
        val type: String,
        val title: String,
        val description: String,
        val image: String,
        val hasDub: Boolean,
        val genres: ArrayList<String>?
    )
}

@Serializable
data class FetchAnime(
    val id: String,
    val title: String,
    val description: String,
    val images: Images,
    val content_provider: String,
    val audio_locales: ArrayList<String>,
    val subtitle_locales: ArrayList<String>,
    val maturity_ratings: ArrayList<String>,
    val is_simulcast: Boolean,
)

@Serializable
data class FetchResult(
    val total: Int,
    val data: ArrayList<FetchAnime>
)

@Serializable
data class SeasonResult(
    val total: Int,
    val data: ArrayList<Season>
) {
    @Serializable
    data class Season(
        val id: String,
        val season_number: Int
    )
}

@Serializable
data class EpisodeResult(
    val total: Int,
    val data: ArrayList<Episode>
) {
    @Serializable
    data class Episode(
        val title: String,
        @SerialName("sequence_number")
        val episode_number: Float,
        val episode: String,
        @SerialName("episode_air_date")
        val airDate: String,
        val versions: ArrayList<Version>
    ) {
        @Serializable
        data class Version(
            val audio_locale: String,
            @SerialName("guid")
            val id: String
        )
    }
}

@Serializable
data class ConsumetEpsiodes(
    val episodes: JsonObject
)

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
    val ids: List<Pair<String, String>>
)

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

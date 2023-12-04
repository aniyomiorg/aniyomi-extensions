package eu.kanade.tachiyomi.animeextension.en.allanimechi

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.Serializable

@Serializable
data class PopularResult(
    val data: PopularResultData,
) {
    @Serializable
    data class PopularResultData(
        val queryPopular: QueryPopularData,
    ) {
        @Serializable
        data class QueryPopularData(
            val recommendations: List<Recommendation>,
        ) {
            @Serializable
            data class Recommendation(
                val anyCard: Card? = null,
            ) {
                @Serializable
                data class Card(
                    val _id: String,
                    val name: String,
                    val thumbnail: String,
                    val englishName: String? = null,
                    val nativeName: String? = null,
                ) {
                    fun toSAnime(titlePref: String): SAnime = SAnime.create().apply {
                        title = when (titlePref) {
                            "romaji" -> name
                            "eng" -> englishName ?: name
                            else -> nativeName ?: name
                        }
                        thumbnail_url = thumbnail
                        url = _id
                    }
                }
            }
        }
    }
}

@Serializable
data class SearchResult(
    val data: SearchResultData,
) {
    @Serializable
    data class SearchResultData(
        val shows: SearchResultShows,
    ) {
        @Serializable
        data class SearchResultShows(
            val edges: List<SearchResultEdge>,
        ) {
            @Serializable
            data class SearchResultEdge(
                val _id: String,
                val name: String,
                val thumbnail: String,
                val englishName: String? = null,
                val nativeName: String? = null,
            ) {
                fun toSAnime(titlePref: String): SAnime = SAnime.create().apply {
                    title = when (titlePref) {
                        "romaji" -> name
                        "eng" -> englishName ?: name
                        else -> nativeName ?: name
                    }
                    thumbnail_url = thumbnail
                    url = _id
                }
            }
        }
    }
}

@Serializable
data class DetailsResult(
    val data: DataShow,
) {
    @Serializable
    data class DataShow(
        val show: SeriesShows,
    ) {
        @Serializable
        data class SeriesShows(
            val thumbnail: String,
            val genres: List<String>? = null,
            val studios: List<String>? = null,
            val season: AirSeason? = null,
            val status: String? = null,
            val score: Float? = null,
            val type: String? = null,
            val description: String? = null,
        ) {
            @Serializable
            data class AirSeason(
                val quarter: String,
                val year: Int,
            )
        }
    }
}

@Serializable
data class SeriesResult(
    val data: DataShow,
) {
    @Serializable
    data class DataShow(
        val show: SeriesShows,
    ) {
        @Serializable
        data class SeriesShows(
            val _id: String,
            val availableEpisodesDetail: AvailableEps,
        ) {
            @Serializable
            data class AvailableEps(
                val sub: List<String>? = null,
                val dub: List<String>? = null,
            )
        }
    }
}

@Serializable
data class EpisodeResult(
    val data: DataEpisode,
) {
    @Serializable
    data class DataEpisode(
        val episode: Episode,
    ) {
        @Serializable
        data class Episode(
            val sourceUrls: List<SourceUrl>,
        ) {
            @Serializable
            data class SourceUrl(
                val sourceUrl: String,
                val type: String,
                val sourceName: String,
                val priority: Float = 0F,
            )
        }
    }
}

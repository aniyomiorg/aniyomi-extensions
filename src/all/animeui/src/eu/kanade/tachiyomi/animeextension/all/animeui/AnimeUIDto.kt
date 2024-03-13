package eu.kanade.tachiyomi.animeextension.all.animeui

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor

@Serializable
data class HomeListResponse(
    val latestAnimes: List<AnimeObject>,
    val trendingAnimes: List<AnimeObject>,
)

@Serializable
data class DirectoryResponse(
    val animes: List<AnimeObject>,
    val page: Int,
    val pages: Int,
)

@Serializable
data class AnimeObject(
    val title: String,
    val title_english: String? = null,
    val title_japanese: String? = null,
    val slug: String,
    val img_url: String,
) {
    fun toSAnime(baseUrl: String, titlePref: String): SAnime = SAnime.create().apply {
        thumbnail_url = "$baseUrl/_next/image?url=/api/images$img_url&w=640&q=75"
        title = when (titlePref) {
            "native" -> title_japanese
            "english" -> title_english
            else -> this@AnimeObject.title
        } ?: this@AnimeObject.title
        url = "/anime/$slug"
    }
}

@Serializable
data class AnimeData(
    val props: PropsObject,
) {
    @Serializable
    data class PropsObject(
        val pageProps: PagePropsObject,
    ) {
        @Serializable
        data class PagePropsObject(
            val animeData: AnimeDataObject,
        ) {
            @Serializable
            data class AnimeDataObject(
                val anime: AnimeInfo,
                val genres: List<String>? = null,
                val episodes: List<EpisodeObject>,
            ) {
                @Serializable
                data class AnimeInfo(
                    val synopsis: String? = null,
                    val type_name: String? = null,
                    val rating_name: String? = null,
                    val year: Int? = null,
                    val season_name: String? = null,
                    val status_id: Int? = null,
                )

                fun toSAnime(): SAnime = SAnime.create().apply {
                    description = buildString {
                        anime.synopsis?.let { append(it + "\n\n") }
                        anime.type_name?.let { append("Type: $it\n") }
                        anime.year?.let {
                            append("Release: $it ${anime.season_name ?: ""}\n")
                        }
                        anime.rating_name?.let { append(it) }
                    }
                    status = when (anime.status_id) {
                        2 -> SAnime.ONGOING
                        3 -> SAnime.COMPLETED
                        else -> SAnime.UNKNOWN
                    }
                    genre = genres?.joinToString(", ")
                }

                @Serializable
                data class EpisodeObject(
                    val title: String? = null,
                    val number: Float,
                    val cid: String,
                ) {
                    fun toSEpisode(animeSlug: String): SEpisode = SEpisode.create().apply {
                        val epName = if (floor(number) == ceil(number)) {
                            number.toInt().toString()
                        } else {
                            number.toString()
                        }
                        name = "Ep. $epName${title?.let { " - $it" } ?: ""}"
                        episode_number = number
                        url = "/watch/$animeSlug/$epName"
                    }
                }
            }
        }
    }
}

@Serializable
data class EpisodeData(
    val props: PropsObject,
) {
    @Serializable
    data class PropsObject(
        val pageProps: PagePropsObject,
    ) {
        @Serializable
        data class PagePropsObject(
            val episodeData: EpisodeDataObject,
        ) {
            @Serializable
            data class EpisodeDataObject(
                val episode: EpisodeInfoObject,
                val servers: List<ServerObject>,
                val subtitlesJson: String? = null,
            ) {
                @Serializable
                data class EpisodeInfoObject(
                    val cid: String,
                )

                @Serializable
                data class ServerObject(
                    val name: String,
                    val url: String,
                    val status: Int,
                )
            }
        }
    }
}

@Serializable
data class SubtitleObject(
    val url: String,
    val subtitle_name: String,
)

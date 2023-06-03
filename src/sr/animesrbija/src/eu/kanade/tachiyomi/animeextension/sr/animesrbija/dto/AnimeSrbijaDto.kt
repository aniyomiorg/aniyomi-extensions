package eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PagePropsDto<T>(@SerialName("pageProps") val data: T)

@Serializable
data class SearchPageDto(val anime: List<SearchAnimeDto>)

@Serializable
data class SearchAnimeDto(
    val title: String,
    val slug: String,
    val img: String,
) {
    val imgPath by lazy { "/_next/image?url=$img&w=1080&q=75" }
}

@Serializable
data class LatestUpdatesDto(
    @SerialName("newEpisodes")
    val data: List<LatestEpisodeUpdateDto>,
) {
    @Serializable
    data class LatestEpisodeUpdateDto(val anime: SearchAnimeDto)

    val animes by lazy { data.map { it.anime } }
}

@Serializable
data class AnimeDetailsDto(val anime: AnimeDetailsData)

@Serializable
data class AnimeDetailsData(
    val aired: String?,
    val desc: String?,
    val genres: List<String>,
    val img: String,
    val season: String?,
    val slug: String,
    val status: String?,
    val studios: List<String>,
    val subtitle: String?,
    val title: String,
) {
    val imgPath by lazy { "/_next/image?url=$img&w=1080&q=75" }
}

@Serializable
data class EpisodesDto(val anime: EpisodeListDto) {
    @Serializable
    data class EpisodeListDto(val episodes: List<EpisodeDto>)

    @Serializable
    data class EpisodeDto(
        val slug: String,
        val number: Int,
        val filler: Boolean,
    )

    val episodes by lazy { anime.episodes }
}

@Serializable
data class EpisodeVideo(val episode: PlayersDto) {
    @Serializable
    data class PlayersDto(
        val player1: String?,
        val player2: String?,
        val player3: String?,
        val player4: String?,
        val player5: String?,
    )

    val links by lazy {
        listOfNotNull(
            episode.player1,
            episode.player2,
            episode.player3,
            episode.player4,
            episode.player5,
        )
    }
}

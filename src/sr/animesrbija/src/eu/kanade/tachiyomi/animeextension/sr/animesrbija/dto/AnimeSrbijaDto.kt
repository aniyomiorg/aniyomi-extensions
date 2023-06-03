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

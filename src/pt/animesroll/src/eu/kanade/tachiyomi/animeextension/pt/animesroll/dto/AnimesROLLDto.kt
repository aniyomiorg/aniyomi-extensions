package eu.kanade.tachiyomi.animeextension.pt.animesroll.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PagePropDto<T>(val pageProps: DataPropDto<T>) {
    val data by lazy { pageProps.data }
}

@Serializable
data class DataPropDto<T>(val data: T)

@Serializable
data class LatestAnimeDto(
    @SerialName("data_releases")
    val animes: List<AnimeDataDto>
)

@Serializable
data class AnimeDataDto(
    @SerialName("slug_serie")
    val slug: String,
    @SerialName("titulo")
    val title: String
)

package eu.kanade.tachiyomi.animeextension.pt.animesroll.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
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
    val anititle: String
) {
    fun toSAnime(): SAnime {
        return SAnime.create().apply {
            url = "/anime/$slug"
            thumbnail_url = "https://static.anroll.net/images/animes/capas/$slug.jpg"
            title = anititle
        }
    }
}

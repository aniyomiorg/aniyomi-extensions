package eu.kanade.tachiyomi.animeextension.tr.anizm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchItemDto(
    @SerialName("info_title") val title: String,
    @SerialName("info_othernames") val othernames: String?,
    @SerialName("info_japanese") val japanese: String?,
    @SerialName("info_slug") val slug: String,
    @SerialName("info_studios") val studios: String?,
    @SerialName("info_poster") val thumbnail: String,
    @SerialName("info_year") val year: String?,
    @SerialName("info_malpoint") val malpoint: Double?,
) {
    val names by lazy { listOfNotNull(othernames, japanese, title) }
}

package eu.kanade.tachiyomi.animeextension.pt.hentaistube.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ItemsListDto(
    @SerialName("encontrado")
    val items: List<SearchItemDto>,
)

@Serializable
data class SearchItemDto(
    @SerialName("titulo") val title: String,
    @SerialName("imagem") val thumbnail: String,
    @SerialName("estudio") val studios: String,
    val url: String,
    val tags: String,
)

package eu.kanade.tachiyomi.animeextension.es.hentaila

import kotlinx.serialization.Serializable

@Serializable
data class HentailaDto(
    val id: String,
    val slug: String,
    val title: String,
    val type: String,
)

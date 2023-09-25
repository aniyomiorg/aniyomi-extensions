package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import kotlinx.serialization.Serializable

@Serializable
data class EncryptedResponseDto(val data: String)

@Serializable
data class SourceDto(
    val source: List<FileDto>,
)

@Serializable
data class FileDto(
    val file: String,
)

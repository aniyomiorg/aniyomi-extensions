package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamHeadDto(
    @SerialName("stream_source") val source: String,
)

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

package eu.kanade.tachiyomi.lib.gogostreamextractor

import kotlinx.serialization.Serializable

@Serializable
data class EncryptedDataDto(val data: String)

@Serializable
data class DecryptedDataDto(val source: List<SourceDto>)

@Serializable
data class SourceDto(val file: String, val label: String = "", val type: String)

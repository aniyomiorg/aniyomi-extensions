package eu.kanade.tachiyomi.animeextension.fr.animevostfr.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class CdopeResponse(
    val data: List<FileObject>,
) {
    @Serializable
    data class FileObject(
        val file: String,
        val label: String,
        val type: String,
    )
}

class CdopeExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val id = url.substringAfter("/v/")
        val body = "r=&d=cdopetimes.xyz".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val headers = Headers.headersOf(
            "Accept", "*/*",
            "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
            "Host", "cdopetimes.xyz",
            "Origin", "https://cdopetimes.xyz",
            "Referer", url,
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/109.0",
            "X-Requested-With", "XMLHttpRequest",
        )
        val response = client.newCall(
            POST("https://cdopetimes.xyz/api/source/$id", body = body, headers = headers),
        ).execute()

        Json { ignoreUnknownKeys = true }.decodeFromString<CdopeResponse>(response.body.string()).data.forEach { file ->
            val videoHeaders = Headers.headersOf(
                "Accept",
                "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                "Referer",
                "https://cdopetimes.xyz/",
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/109.0",
            )

            videoList.add(
                Video(
                    file.file,
                    "${file.label} (Cdope - ${file.type})",
                    file.file,
                    headers = videoHeaders,
                ),
            )
        }

        return videoList
    }
}

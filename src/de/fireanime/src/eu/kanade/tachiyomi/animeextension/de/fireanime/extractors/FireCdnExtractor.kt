package eu.kanade.tachiyomi.animeextension.de.fireanime.extractors

import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.FireCdnFileDto
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient

class FireCdnExtractor(private val client: OkHttpClient, private val json: Json) {
    fun videoFromUrl(url: String, quality: String): Video? {
        // Check if video is available. It is faster than waiting for the api response status
        if (client.newCall(GET(url)).execute().body!!.string().contains("still converting"))
            return null

        val fileName = url.split("/").last()
        val fileRequest = POST(
            "https://firecdn.cc/api/stream/deploy",
            body = FormBody.Builder()
                .add("file", fileName)
                .build()
        )

        val file = json.decodeFromString(FireCdnFileDto.serializer(), client.newCall(fileRequest).execute().body!!.string())
        val videoUrl = "${file.proxy.replace("\\", "")}/share/${file.file}/apple.mp4"
        return Video(url, quality, videoUrl)
    }
}

package eu.kanade.tachiyomi.animeextension.en.vidembed.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class XstreamcdnExtractor(private val client: OkHttpClient, private val json: Json) {
    fun videosFromUrl(url: String, lang: String = ""): List<Video> {
        val headers = Headers.Builder().set("referer", url.substringBefore("#")).build()
        val apiUrl = url.replace("/e/", "/api/source/")
        val resp = json.decodeFromString<LinkData>(
            client.newCall(POST(apiUrl, headers)).execute().body!!.string()
        )
        val videoList = mutableListOf<Video>()

        resp.data!!.map {
            videoList.add(
                Video(
                    url = it.file,
                    quality = "Xstreamcdn: " + it.label + if (lang != "") " - $lang" else "",
                    videoUrl = it.file
                )
            )
        }
        return videoList
    }
}

@Serializable
data class LinkData(
    val success: Boolean,
    val is_vr: Boolean,
    val data: List<VideoData>?
)

@Serializable
data class VideoData(
    val file: String,
    val label: String,
    val type: String,
)

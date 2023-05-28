package eu.kanade.tachiyomi.animeextension.de.cinemathek.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamlareExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val id = url.split("/").last()
        val playlist = client.newCall(
            POST(
                "https://slwatch.co/api/video/stream/get",
                body = "{\"id\":\"$id\"}"
                    .toRequestBody("application/json".toMediaType()),
            ),
        ).execute().body.string()

        val type = playlist.substringAfter("\"type\":\"").substringBefore("\"")
        return if (type == "hls") {
            val masterPlaylistUrl = playlist.substringAfter("\"file\":\"").substringBefore("\"").replace("\\/", "/")
            val masterPlaylist = client.newCall(GET(masterPlaylistUrl)).execute().body.string()

            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n").let { urlPart ->
                    when {
                        !urlPart.startsWith("http") ->
                            masterPlaylistUrl.substringBefore("master.m3u8") + urlPart
                        else -> urlPart
                    }
                }
                Video(videoUrl, "Streamlare:$quality", videoUrl)
            }
        } else {
            val separator = "\"label\":\""
            playlist.substringAfter(separator).split(separator).map {
                val quality = it.substringAfter(separator).substringBefore("\",") + " (Sl-mp4)"
                val apiUrl = it.substringAfter("\"file\":\"").substringBefore("\",")
                    .replace("\\", "")
                val response = client.newCall(POST(apiUrl)).execute()
                val videoUrl = response.request.url.toString()
                Video(videoUrl, "Streamlare:$quality", videoUrl)
            }
        }
    }
}

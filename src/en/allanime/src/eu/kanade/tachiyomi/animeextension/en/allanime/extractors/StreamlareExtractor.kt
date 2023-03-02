package eu.kanade.tachiyomi.animeextension.en.allanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamlareExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val id = url.split("/").last()
        val videoList = mutableListOf<Video>()
        val playlist = client.newCall(
            POST(
                "https://slwatch.co/api/video/stream/get",
                body = "{\"id\":\"$id\"}"
                    .toRequestBody("application/json".toMediaType()),
            ),
        ).execute().body.string()

        val type = playlist.substringAfter("\"type\":\"").substringBefore("\"")
        if (type == "hls") {
            val masterPlaylistUrl = playlist.substringAfter("\"file\":\"").substringBefore("\"").replace("\\/", "/")
            val masterPlaylist = client.newCall(GET(masterPlaylistUrl)).execute().body.string()

            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                var videoUrl = it.substringAfter("\n").substringBefore("\n")
                if (!videoUrl.startsWith("http")) videoUrl = "${masterPlaylistUrl.substringBefore("master.m3u8")}$videoUrl"
                videoList.add(Video(videoUrl, "$quality (Streamlare)", videoUrl))
            }
        } else {
            playlist.substringAfter("\"label\":\"").split("\"label\":\"").forEach {
                val quality = it.substringAfter("\"label\":\"").substringBefore("\",") + " (Sl-mp4)"
                val token = it.substringAfter("\"file\":\"https:\\/\\/larecontent.com\\/video?token=")
                    .substringBefore("\",")
                val response = client.newCall(POST("https://larecontent.com/video?token=$token")).execute()
                val videoUrl = response.request.url.toString()
                videoList.add(Video(videoUrl, "$quality (Streamlare)", videoUrl))
            }
        }
        return videoList
    }
}

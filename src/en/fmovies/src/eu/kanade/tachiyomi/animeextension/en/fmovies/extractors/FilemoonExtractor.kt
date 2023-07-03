package eu.kanade.tachiyomi.animeextension.en.fmovies.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class FilemoonExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val subtitleList = mutableListOf<Track>()
        val subInfoUrl = url.toHttpUrl().queryParameter("sub.info")
        runCatching {
            if (subInfoUrl != null) {
                val subData = client.newCall(GET(subInfoUrl, headers)).execute().parseAs<List<FMoviesSubs>>()
                subtitleList.addAll(
                    subData.map {
                        Track(it.file, it.label)
                    },
                )
            }
        }

        val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(m3u8)")!!.data()
        val masterUrl = JsUnpacker.unpackAndCombine(jsE)?.substringAfter("{file:\"")
            ?.substringBefore("\"}") ?: return emptyList()

        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()
        val videoList = mutableListOf<Video>()

        val subtitleRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")
        subtitleList.addAll(
            subtitleRegex.findAll(masterPlaylist).map {
                Track(
                    it.groupValues[2],
                    it.groupValues[1],
                )
            },
        )

        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
            .forEach {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                val videoUrl = it.substringAfter("\n").substringBefore("\n")

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                videoList.add(Video(videoUrl, prefix + quality, videoUrl, headers = videoHeaders, subtitleTracks = subtitleList))
            }
        return videoList
    }

    @Serializable
    data class FMoviesSubs(
        val file: String,
        val label: String,
    )

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }
}

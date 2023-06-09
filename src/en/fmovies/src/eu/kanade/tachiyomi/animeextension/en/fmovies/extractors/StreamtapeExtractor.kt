package eu.kanade.tachiyomi.animeextension.en.fmovies.extractors

import eu.kanade.tachiyomi.animeextension.en.fmovies.FMoviesSubs
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class StreamtapeExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, quality: String = "Streamtape"): List<Video> {
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

        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            // ["https", "", "<domain>", "<???>", "<id>", ...]
            val id = url.split("/").getOrNull(4) ?: return emptyList()
            baseUrl + id
        } else { url }
        val document = client.newCall(GET(newUrl)).execute().asJsoup()
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return emptyList()
        val videoUrl = "https:" + script.substringBefore("'") +
            script.substringAfter("+ ('xcd").substringBefore("'")
        return listOf(Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleList))
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }
}

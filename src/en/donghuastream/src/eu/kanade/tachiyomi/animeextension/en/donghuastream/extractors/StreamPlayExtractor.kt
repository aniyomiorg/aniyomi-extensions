package eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class StreamPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).execute().asJsoup()

        val apiUrl = document.selectFirst("script:containsData(/api/)")
            ?.data()
            ?.substringAfter("url:")
            ?.substringAfter("\"")
            ?.substringBefore("\"")
            ?: return emptyList()

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", apiUrl.toHttpUrl().host)
            add("Referer", url)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val apiResponse = client.newCall(
            GET("$apiUrl&_=${System.currentTimeMillis() / 1000}", headers = apiHeaders),
        ).execute().parseAs<APIResponse>()

        val subtitleList = apiResponse.tracks?.let { t ->
            t.map { Track(it.file, it.label) }
        } ?: emptyList()

        return apiResponse.sources.flatMap { source ->
            val sourceUrl = source.file.replace("^//".toRegex(), "https://")
            playlistUtils.extractFromHls(sourceUrl, referer = url, subtitleList = subtitleList, videoNameGen = { q -> "$prefix$q (StreamPlay)" })
        }
    }

    @Serializable
    data class APIResponse(
        val sources: List<SourceObject>,
        val tracks: List<TrackObject>? = null,
    ) {
        @Serializable
        data class SourceObject(
            val file: String,
        )

        @Serializable
        data class TrackObject(
            val file: String,
            val label: String,
        )
    }
}

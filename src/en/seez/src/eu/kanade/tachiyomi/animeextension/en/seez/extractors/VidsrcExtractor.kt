package eu.kanade.tachiyomi.animeextension.en.seez.extractors

import eu.kanade.tachiyomi.animeextension.en.seez.VrfHelper
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

// Stolen from fmovies
class VidsrcExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()
    private val vrfHelper by lazy { VrfHelper(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val host = "vidstream.pro"

        val referer = "https://$host/"

        val httpUrl = url.toHttpUrl()

        val query = "${httpUrl.pathSegments.last()}?t=${httpUrl.queryParameter("t")!!}"
        val rawUrl = vrfHelper.getVidSrc(query, host)

        val refererHeaders = headers.newBuilder().apply {
            add("Referer", referer)
        }.build()

        val infoJson = client.newCall(
            GET(rawUrl, headers = refererHeaders),
        ).execute().parseAs<VidsrcResponse>()

        val subtitleList = httpUrl.queryParameter("sub.info")?.let {
            client.newCall(
                GET(it, headers = refererHeaders),
            ).execute().parseAs<List<FMoviesSubs>>().map {
                Track(it.file, it.label)
            }
        } ?: emptyList()

        return infoJson.result.sources.distinctBy { it.file }.flatMap {
            playlistUtils.extractFromHls(it.file, subtitleList = subtitleList, referer = referer, videoNameGen = { q -> "$name - $q" })
        }
    }

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { it }): T {
        val responseBody = use { transform(it.body.string()) }
        return json.decodeFromString(responseBody)
    }

    @Serializable
    data class VidsrcResponse(
        val result: ResultObject,
    ) {
        @Serializable
        data class ResultObject(
            val sources: List<SourceObject>,
        ) {
            @Serializable
            data class SourceObject(
                val file: String,
            )
        }
    }

    @Serializable
    data class FMoviesSubs(
        val file: String,
        val label: String,
    )
}

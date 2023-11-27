package eu.kanade.tachiyomi.animeextension.en.allanimechi.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.en.allanimechi.AllAnimeChi
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

class InternalExtractor(private val client: OkHttpClient, private val apiHeaders: Headers) {

    private val blogUrl = "aHR0cHM6Ly9ibG9nLmFsbGFuaW1lLnBybw==".decodeBase64()

    private val playlistHeaders = Headers.headersOf(
        "User-Agent",
        "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 5 Build/TQ3A.230705.001.B4)",
    )

    private val playlistUtils by lazy { PlaylistUtils(client, playlistHeaders) }

    private val json: Json by injectLazy()

    fun videosFromServer(server: AllAnimeChi.Server, removeRaw: Boolean): List<Pair<Video, Float>> {
        val blogHeaders = apiHeaders.newBuilder().apply {
            set("host", blogUrl.toHttpUrl().host)
        }.build()

        val videoData = client.newCall(
            GET(blogUrl + server.sourceUrl.replace("clock?id=", "clock.json?id="), blogHeaders),
        ).execute().parseAs<VideoData>()

        val videoList = videoData.links.flatMap {
            when {
                it.hls == true -> getFromHls(server, it, removeRaw)
                it.mp4 == true -> getFromMp4(server, it)
                else -> emptyList()
            }
        }

        return videoList
    }

    private fun getFromMp4(server: AllAnimeChi.Server, data: VideoData.LinkObject): List<Pair<Video, Float>> {
        val baseName = "${server.sourceName} - ${data.resolutionStr}"
        val video = Video(data.link, baseName, data.link, headers = playlistHeaders)
        return listOf(
            Pair(video, server.priority),
        )
    }

    private fun getFromHls(server: AllAnimeChi.Server, data: VideoData.LinkObject, removeRaw: Boolean): List<Pair<Video, Float>> {
        if (removeRaw && data.resolutionStr.contains("raw", true)) return emptyList()

        val linkHost = data.link.toHttpUrl().host
        // Doesn't seem to work
        if (server.sourceName.equals("Luf-mp4", true) && linkHost.contains("maverickki")) return emptyList()

        // Hardcode some names
        val baseName = if (linkHost.contains("crunchyroll")) {
            "Crunchyroll - ${data.resolutionStr}"
                .replace("m_SUB", "SoftSub")
                .replace("vo_SUB", "Sub")
                .replace("SUB", "Sub")
        } else if (linkHost.contains("vrv")) {
            "Vrv - ${data.resolutionStr}"
                .replace("m_SUB", "SoftSub")
                .replace("vo_SUB", "Sub")
                .replace("SUB", "Sub")
        } else {
            "${server.sourceName} - ${data.resolutionStr}"
                .replace("Luf-mp4", "Luf-mp4 (gogo)")
        }

        // Get stuff

        val hlsHeaders = playlistHeaders.newBuilder().apply {
            set("host", linkHost)
        }.build()

        val masterHeaders = hlsHeaders.newBuilder().apply {
            data.headers?.entries?.forEach {
                set(it.key, it.value)
            }
        }.build()

        val videoList = playlistUtils.extractFromHls(
            data.link,
            videoNameGen = { q -> "$baseName - $q" },
            masterHeadersGen = { _, _ -> masterHeaders },
        )

        return videoList.map {
            Pair(it, server.priority)
        }
    }

    // ============================= Utilities ==============================

    @Serializable
    data class VideoData(
        val links: List<LinkObject>,
    ) {
        @Serializable
        data class LinkObject(
            val link: String,
            val resolutionStr: String,
            val mp4: Boolean? = null,
            val hls: Boolean? = null,
            val headers: Map<String, String>? = null,
        )
    }

    private fun String.decodeBase64(): String {
        return String(Base64.decode(this, Base64.DEFAULT))
    }

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { it }): T {
        val responseBody = use { transform(it.body.string()) }
        return json.decodeFromString(responseBody)
    }
}

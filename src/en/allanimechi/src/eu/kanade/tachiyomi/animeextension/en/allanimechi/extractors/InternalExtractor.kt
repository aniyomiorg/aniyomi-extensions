package eu.kanade.tachiyomi.animeextension.en.allanimechi.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.en.allanimechi.AllAnimeChi
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class InternalExtractor(private val client: OkHttpClient, private val apiHeaders: Headers, private val headers: Headers) {

    private val blogUrl = "aHR0cHM6Ly9ibG9nLmFsbGFuaW1lLnBybw==".decodeBase64()

    private val playlistHeaders = Headers.headersOf(
        "User-Agent",
        "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 5 Build/TQ3A.230705.001.B4)",
    )

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val json: Json by injectLazy()

    fun videosFromServer(server: AllAnimeChi.Server, useHosterName: Boolean, removeRaw: Boolean): List<Pair<Video, Float>> {
        val blogHeaders = apiHeaders.newBuilder().apply {
            set("host", blogUrl.toHttpUrl().host)
        }.build()

        val videoData = client.newCall(
            GET(blogUrl + server.sourceUrl.replace("clock?id=", "clock.json?id="), blogHeaders),
        ).execute().parseAs<VideoData>()

        val videoList = videoData.links.flatMap {
            when {
                it.hls == true -> getFromHls(server, it, useHosterName, removeRaw)
                it.mp4 == true -> getFromMp4(server, it, useHosterName)
                else -> emptyList()
            }
        }

        return videoList
    }

    private fun getFromMp4(server: AllAnimeChi.Server, data: VideoData.LinkObject, useHosterName: Boolean): List<Pair<Video, Float>> {
        val host = if (useHosterName) getHostName(data.link, server.sourceName) else server.sourceName

        val baseName = "$host - ${data.resolutionStr}"
        val video = Video(data.link, baseName, data.link, headers = playlistHeaders)
        return listOf(
            Pair(video, server.priority),
        )
    }

    private fun getFromHls(server: AllAnimeChi.Server, data: VideoData.LinkObject, useHosterName: Boolean, removeRaw: Boolean): List<Pair<Video, Float>> {
        if (removeRaw && data.resolutionStr.contains("raw", true)) return emptyList()
        val host = if (useHosterName) getHostName(data.link, server.sourceName) else server.sourceName

        val linkHost = data.link.toHttpUrl().host

        // Doesn't seem to work
        if (server.sourceName.equals("Luf-mp4", true)) {
            return getFromGogo(server, data, host)
        }

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
            "$host - ${data.resolutionStr}"
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
            videoNameGen = { q -> "$baseName - ${data.resolutionStr} - $q" },
            masterHeadersGen = { _, _ -> masterHeaders },
        )

        return videoList.map {
            Pair(it, server.priority)
        }
    }

    private fun getFromGogo(server: AllAnimeChi.Server, data: VideoData.LinkObject, hostName: String): List<Pair<Video, Float>> {
        val host = data.link.toHttpUrl().host

        // Seems to be dead
        if (host.contains("maverickki", true)) return emptyList()

        val videoList = playlistUtils.extractFromHls(
            data.link,
            videoNameGen = { q -> "$hostName - ${data.resolutionStr} - $q" },
            referer = "https://playtaku.net/",
        )

        return videoList.map {
            Pair(it, server.priority)
        }
    }

    // ============================= Utilities ==============================

    private fun getHostName(host: String, fallback: String): String {
        return host.toHttpUrlOrNull()?.host?.split(".")?.let {
            it.getOrNull(it.size - 2)?.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString()
            }
        } ?: fallback
    }

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
}

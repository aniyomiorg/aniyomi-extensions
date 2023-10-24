package eu.kanade.tachiyomi.animeextension.en.allanime.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class AllAnimeExtractor(private val client: OkHttpClient, private val headers: Headers, private val siteUrl: String) {

    private val json: Json by injectLazy()

    private fun bytesIntoHumanReadable(bytes: Long): String {
        val kilobyte: Long = 1000
        val megabyte = kilobyte * 1000
        val gigabyte = megabyte * 1000
        val terabyte = gigabyte * 1000
        return if (bytes in 0 until kilobyte) {
            "$bytes b/s"
        } else if (bytes in kilobyte until megabyte) {
            (bytes / kilobyte).toString() + " kb/s"
        } else if (bytes in megabyte until gigabyte) {
            (bytes / megabyte).toString() + " mb/s"
        } else if (bytes in gigabyte until terabyte) {
            (bytes / gigabyte).toString() + " gb/s"
        } else if (bytes >= terabyte) {
            (bytes / terabyte).toString() + " tb/s"
        } else {
            "$bytes bits/s"
        }
    }

    fun videoFromUrl(url: String, name: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val endPoint = json.decodeFromString<VersionResponse>(
            client.newCall(GET("$siteUrl/getVersion")).execute().body.string(),
        ).episodeIframeHead

        val resp = client.newCall(
            GET(endPoint + url.replace("/clock?", "/clock.json?")),
        ).execute()

        if (resp.code != 200) {
            return emptyList()
        }

        val body = resp.body.string()
        val linkJson = json.decodeFromString<VideoLink>(body)

        for (link in linkJson.links) {
            val subtitles = mutableListOf<Track>()
            if (!link.subtitles.isNullOrEmpty()) {
                subtitles.addAll(
                    link.subtitles.map { sub ->
                        val label = if (sub.label != null) {
                            " - ${sub.label}"
                        } else {
                            ""
                        }
                        Track(sub.src, Locale(sub.lang).displayLanguage + label)
                    },
                )
            }

            if (link.mp4 == true) {
                videoList.add(
                    Video(
                        link.link,
                        "Original ($name - ${link.resolutionStr})",
                        link.link,
                        subtitleTracks = subtitles,
                    ),
                )
            } else if (link.hls == true) {
                val newClient = OkHttpClient()

                val masterHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", link.link.toHttpUrl().host)
                    .add("Origin", endPoint)
                    .add("Referer", "$endPoint/")
                    .build()

                val resp = runCatching {
                    newClient.newCall(
                        GET(link.link, headers = masterHeaders),
                    ).execute()
                }.getOrNull()

                if (resp != null && resp.code == 200) {
                    val masterPlaylist = resp.body.string()

                    val audioList = mutableListOf<Track>()
                    if (masterPlaylist.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
                        val audioInfo = masterPlaylist.substringAfter("#EXT-X-MEDIA:TYPE=AUDIO")
                            .substringBefore("\n")
                        val language = audioInfo.substringAfter("NAME=\"").substringBefore("\"")
                        val url = audioInfo.substringAfter("URI=\"").substringBefore("\"")
                        audioList.add(
                            Track(url, language),
                        )
                    }

                    if (!masterPlaylist.contains("#EXT-X-STREAM-INF:")) {
                        return if (audioList.isEmpty()) {
                            listOf(Video(link.link, "$name - ${link.resolutionStr}", link.link, subtitleTracks = subtitles, headers = masterHeaders))
                        } else {
                            listOf(Video(link.link, "$name - ${link.resolutionStr}", link.link, subtitleTracks = subtitles, audioTracks = audioList, headers = masterHeaders))
                        }
                    }

                    masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                        .forEach {
                            val bandwidth = if (it.contains("AVERAGE-BANDWIDTH")) {
                                " " + bytesIntoHumanReadable(it.substringAfter("AVERAGE-BANDWIDTH=").substringBefore(",").toLong())
                            } else {
                                ""
                            }

                            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p$bandwidth ($name - ${link.resolutionStr})"
                            var videoUrl = it.substringAfter("\n").substringBefore("\n")

                            if (!videoUrl.startsWith("http")) {
                                videoUrl = resp.request.url.toString().substringBeforeLast("/") + "/$videoUrl"
                            }

                            val plHeaders = headers.newBuilder()
                                .add("Accept", "*/*")
                                .add("Host", videoUrl.toHttpUrl().host)
                                .add("Origin", endPoint)
                                .add("Referer", "$endPoint/")
                                .build()

                            if (audioList.isEmpty()) {
                                videoList.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subtitles, headers = plHeaders))
                            } else {
                                videoList.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subtitles, audioTracks = audioList, headers = plHeaders))
                            }
                        }
                }
            } else if (link.crIframe == true) {
                link.portData!!.streams.forEach {
                    if (it.format == "adaptive_dash") {
                        videoList.add(
                            Video(
                                it.url,
                                "Original (AC - Dash${if (it.hardsub_lang.isEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})",
                                it.url,
                                subtitleTracks = subtitles,
                            ),
                        )
                    } else if (it.format == "adaptive_hls") {
                        val resp = runCatching {
                            client.newCall(
                                GET(it.url, headers = Headers.headersOf("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")),
                            ).execute()
                        }.getOrNull()

                        if (resp != null && resp.code == 200) {
                            val masterPlaylist = resp.body.string()
                            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                                .forEach { t ->
                                    val quality = t.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p (AC - HLS${if (it.hardsub_lang.isEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})"
                                    var videoUrl = t.substringAfter("\n").substringBefore("\n")

                                    videoList.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subtitles))
                                }
                        }
                    }
                }
            } else if (link.dash == true) {
                val audioList = link.rawUrls?.audios?.map {
                    Track(it.url, bytesIntoHumanReadable(it.bandwidth))
                }
                val videos = link.rawUrls?.vids?.map {
                    if (audioList == null) {
                        Video(it.url, "$name - ${it.height} ${bytesIntoHumanReadable(it.bandwidth)}", it.url, subtitleTracks = subtitles)
                    } else {
                        Video(it.url, "$name - ${it.height} ${bytesIntoHumanReadable(it.bandwidth)}", it.url, audioTracks = audioList, subtitleTracks = subtitles)
                    }
                }
                if (videos != null) {
                    videoList.addAll(videos)
                }
            }
        }

        return videoList
    }

    @Serializable
    data class VersionResponse(
        val episodeIframeHead: String,
    )

    @Serializable
    data class VideoLink(
        val links: List<Link>,
    ) {
        @Serializable
        data class Link(
            val link: String,
            val hls: Boolean? = null,
            val mp4: Boolean? = null,
            val dash: Boolean? = null,
            val crIframe: Boolean? = null,
            val resolutionStr: String,
            val subtitles: List<Subtitles>? = null,
            val rawUrls: RawUrl? = null,
            val portData: Stream? = null,
        ) {
            @Serializable
            data class Subtitles(
                val lang: String,
                val src: String,
                val label: String? = null,
            )

            @Serializable
            data class Stream(
                val streams: List<StreamObject>,
            ) {
                @Serializable
                data class StreamObject(
                    val format: String,
                    val url: String,
                    val audio_lang: String,
                    val hardsub_lang: String,
                )
            }

            @Serializable
            data class RawUrl(
                val vids: List<DashStreamObject>? = null,
                val audios: List<DashStreamObject>? = null,
            ) {
                @Serializable
                data class DashStreamObject(
                    val bandwidth: Long,
                    val height: Int,
                    val url: String,
                )
            }
        }
    }
}

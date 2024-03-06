package eu.kanade.tachiyomi.animeextension.en.putlocker.extractors

import eu.kanade.tachiyomi.animeextension.en.putlocker.CryptoAES
import eu.kanade.tachiyomi.animeextension.en.putlocker.EpResp
import eu.kanade.tachiyomi.animeextension.en.putlocker.Sources
import eu.kanade.tachiyomi.animeextension.en.putlocker.SubTrack
import eu.kanade.tachiyomi.animeextension.en.putlocker.VidSource
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class PutServerExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun extractVideo(videoData: Triple<String, String, String>, baseUrl: String): List<Video> {
        val embedUrl = client.newCall(
            GET("$baseUrl/ajax/movie/episode/server/sources/${videoData.second}_${videoData.first}"),
        ).execute()
            .parseAs<EpResp>()
            .src

        val vidReferer = Headers.headersOf("Referer", "$baseUrl/")
        val vidResponse = extractVideoEmbed(embedUrl, vidReferer)
        if (!vidResponse.contains("sources")) return emptyList()
        val vidJson = json.decodeFromString<Sources>(vidResponse)

        return vidJson.sources.flatMap { source ->
            extractVideoLinks(
                source,
                "$baseUrl/",
                extractSubs(vidJson.tracks),
                videoData.third,
            )
        }.ifEmpty {
            if (!vidJson.backupLink.isNullOrBlank()) {
                vidJson.backupLink.let { bakUrl ->
                    val bakResponse = extractVideoEmbed(bakUrl, vidReferer)
                    if (bakResponse.contains("sources")) {
                        val bakJson = json.decodeFromString<Sources>(bakResponse)
                        bakJson.sources.flatMap { bakSource ->
                            extractVideoLinks(
                                bakSource,
                                "$baseUrl/",
                                extractSubs(bakJson.tracks),
                                "${videoData.third} - Backup",
                            )
                        }
                    } else {
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
        }
    }

    private fun extractSubs(tracks: List<SubTrack>?): List<Track> {
        return tracks?.mapNotNull { sub ->
            Track(
                sub.file,
                sub.label ?: return@mapNotNull null,
            )
        } ?: emptyList()
    }

    private fun extractVideoEmbed(embedUrl: String, referer: Headers): String {
        val embedHost = embedUrl.substringBefore("/embed-player")

        val playerResp = client.newCall(GET(embedUrl, referer)).execute().asJsoup()
        val player = playerResp.select("div#player")
        val vidId = "\"${player.attr("data-id")}\""
        val vidHash = player.attr("data-hash")
        val cipher = CryptoAES.encrypt(vidHash, vidId)
        val vidUrl = "$embedHost/ajax/getSources/".toHttpUrl().newBuilder()
            .addQueryParameter("id", cipher.cipherText)
            .addQueryParameter("h", cipher.password)
            .addQueryParameter("a", cipher.iv)
            .addQueryParameter("t", cipher.salt)
            .build().toString()
        return client.newCall(GET(vidUrl, referer)).execute().body.string()
    }

    private fun extractVideoLinks(source: VidSource, vidReferer: String, subsList: List<Track>, serverId: String): List<Video> {
        return if (source.file.endsWith(".m3u8")) {
            playlistUtils.extractFromHls(
                source.file,
                vidReferer,
                videoNameGen = { q -> "$serverId - $q" },
                subtitleList = subsList,
            )
        } else {
            listOf(
                Video(
                    source.file,
                    "$serverId (${source.type})",
                    source.file,
                    subtitleTracks = subsList,
                ),
            )
        }
    }
}

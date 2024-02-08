package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class XBetExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).await().asJsoup()

        val script = doc.selectFirst("script:containsData(playerConfigs =)")?.data()
            ?: return emptyList()

        val host = "https://${url.toHttpUrl().host}"

        val postPath = script.substringAfter("file\":\"").substringBefore('"')
            .replace("\\", "")

        val postHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", host)
            .build()

        val postRes = client.newCall(POST(host + postPath, postHeaders)).await()
            .parseAs<List<VideoItemDto>> { it.replace("[],", "") }

        return postRes.flatMap { video ->
            runCatching {
                val playlistUrl = client.newCall(POST(host + video.path, postHeaders)).await()
                    .body.string()

                playlistUtils.extractFromHls(
                    playlistUrl,
                    url,
                    videoNameGen = { "[${video.title}] XBet - $it" },
                )
            }.getOrElse { emptyList() }
        }
    }

    @Serializable
    data class VideoItemDto(val file: String, val title: String) {
        val path = "/playlist/${file.removeSuffix("~")}.txt"
    }
}

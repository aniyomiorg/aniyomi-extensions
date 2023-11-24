package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

@Serializable
data class TrackDto(val label: String = "", val file: String, val kind: String)

class RapidrameExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val json: Json,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().use { it.asJsoup() }
        val script = doc.selectFirst("script:containsData(eval):containsData(file_link)")?.data()
            ?: return emptyList()

        val unpackedScript = Unpacker.unpack(script).takeIf(String::isNotEmpty)
            ?: return emptyList()

        val subtitles = (script.substringAfter("tracks: ", "").substringBefore("],", "") + "]")
            .let { list ->
                runCatching {
                    val baseUrl = headers["Origin"]!!
                    json.decodeFromString<List<TrackDto>>(list)
                        .filter { it.kind.equals("captions") }
                        .map { Track(baseUrl + it.file, it.label.replace("Forced", "Türkçe")) }
                }.getOrElse { emptyList() }
            }

        val playlistUrl = unpackedScript.substringAfter('"').substringBefore('"')
            .let { String(Base64.decode(it, Base64.DEFAULT)) }

        return playlistUtils.extractFromHls(
            playlistUrl,
            referer = url,
            videoNameGen = { "Rapidrame - $it" },
            subtitleList = subtitles,
        )
    }
}

package eu.kanade.tachiyomi.animeextension.it.toonitalia.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class MaxStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute()
            .asJsoup()

        val location = doc.location()
        if (location.contains("/dd/")) return videosFromCast(location.replace("/dd/", "/cast3/"))

        val scripts = doc.select(SCRIPT_SELECTOR).ifEmpty {
            return emptyList()
        }

        val playlists = scripts.mapNotNull {
            JsUnpacker.unpackAndCombine(it.data())
                ?.substringAfter("src:\"", "")
                ?.substringBefore('"', "")
                ?.takeIf(String::isNotBlank)
        }

        return playlists.flatMap { link ->
            playlistUtils.extractFromHls(link, location, videoNameGen = { "MaxStream - $it" })
        }
    }

    private fun videosFromCast(url: String): List<Video> {
        val script = client.newCall(GET(url, headers)).execute()
            .asJsoup()
            .selectFirst("script:containsData(document.write)")
            ?.data()
            ?: return emptyList()

        val numberList = NUMBER_LIST_REGEX.find(script)?.groupValues?.last()
            ?.split(", ")
            ?.mapNotNull(String::toIntOrNull)
            ?: return emptyList()

        val offset = numberList.first() - 32
        val decodedData = numberList.joinToString("") {
            Char(it - offset).toString()
        }.trim()

        val newHeaders = headers.newBuilder().set("Referer", url).build()
        val newUrl = decodedData.substringAfter("get('").substringBefore("'")
        val docBody = client.newCall(GET(newUrl, newHeaders)).execute()
            .body.string()

        val videoUrl = docBody.substringAfter(".cast('").substringBefore("'")
        return listOf(Video(videoUrl, "MaxStream CAST Scarica", videoUrl, newHeaders))
    }

    companion object {
        private const val SCRIPT_SELECTOR = "script:containsData(eval):containsData(m3u8)"
        private val NUMBER_LIST_REGEX by lazy { Regex("\\[(.*)\\]") }
    }
}

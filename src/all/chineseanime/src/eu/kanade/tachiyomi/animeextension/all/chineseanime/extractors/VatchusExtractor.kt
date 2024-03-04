package eu.kanade.tachiyomi.animeextension.all.chineseanime.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VatchusExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute()
            .asJsoup()

        val script = doc.selectFirst("script:containsData(document.write)")
            ?.data()
            ?: return emptyList()

        val numberList = script.substringAfter(" = [").substringBefore("];")
            .replace("\"", "")
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { String(Base64.decode(it, Base64.DEFAULT)) }
            .mapNotNull { it.filter(Char::isDigit).toIntOrNull() }

        val offset = numberList.first() - 60
        val decodedData = numberList.joinToString("") {
            Char(it - offset).toString()
        }.trim()

        val playlistUrl = decodedData.substringAfter("file:'").substringBefore("'")
        val subs = decodedData.substringAfter("tracks:[").substringBefore("]")
            .split("{")
            .drop(1)
            .filter { it.contains(""""kind":"captions"""") }
            .mapNotNull {
                val trackUrl = it.substringAfter("file\":\"").substringBefore('"')
                    .takeIf { link -> link.startsWith("http") }
                    ?: return@mapNotNull null
                val language = it.substringAfter("label\":\"").substringBefore('"')
                Track(trackUrl, language)
            }

        return playlistUtils.extractFromHls(
            playlistUrl,
            url,
            subtitleList = subs,
            videoNameGen = { prefix + it },
        )
    }
}

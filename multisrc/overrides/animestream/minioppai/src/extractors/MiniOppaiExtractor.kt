package eu.kanade.tachiyomi.animeextension.id.minioppai.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class MiniOppaiExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val playerDoc = client.newCall(GET(url, headers)).execute().asJsoup()
        val scriptData = playerDoc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")
            ?.data()
            ?.let(Unpacker::unpack)
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val baseUrl = "https://" + url.substringAfter("//").substringBefore("/")

        val subs = scriptData.getItems("\"tracks\"", baseUrl) { subUrl, label ->
            Track(subUrl, label)
        }

        return scriptData.getItems("sources", baseUrl) { videoUrl, quality ->
            val videoQuality = "MiniOppai - $quality"
            Video(videoUrl, videoQuality, videoUrl, headers, subtitleTracks = subs)
        }.filterNot { it.url.contains("/uploads/unavailable.mp4") }
    }

    // time to over-engineer things for no reason at all
    private fun <T> String.getItems(key: String, baseUrl: String, transformer: (String, String) -> T): List<T> {
        return substringAfter("$key:[").substringBefore("]")
            .split("{")
            .drop(1)
            .map {
                val url = "$baseUrl${it.extractKey("file")}"
                val label = it.extractKey("label")
                transformer(url, label)
            }
    }

    private fun String.extractKey(key: String) =
        substringAfter(key)
            .substringBefore("}")
            .substringBefore(",")
            .substringAfter(":")
            .trim('"')
            .trim('\'')
}

package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class MVidooExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val body = client.newCall(GET(url)).execute().body.string()

        val url = Regex("""\{var\s?.*?\s?=\s?(\[.*?\])""").find(body)?.groupValues?.get(1)?.let {
            Json.decodeFromString<List<String>>(it.replace("\\x", ""))
                .joinToString("") { t -> t.decodeHex() }.reversed()
                .substringAfter("src=\"").substringBefore("\"")
        } ?: return emptyList()

        return listOf(
            Video(url, "${prefix}MVidoo", url),
        )
    }

    // Stolen from BestDubbedAnime
    private fun String.decodeHex(): String {
        require(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    }
}

package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class CloseloadExtractor(private val client: OkHttpClient, private val headers: Headers) {
    suspend fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).await().asJsoup()
        val script = doc.selectFirst("script:containsData(eval):containsData(PlayerInit)")?.data()
            ?: return emptyList()

        val unpackedScript = Unpacker.unpack(script).takeIf(String::isNotEmpty)
            ?: return emptyList()

        val varName = unpackedScript.substringAfter("atob(").substringBefore(")")
        val playlistUrl = unpackedScript.getProperty("$varName=")
            .let { String(Base64.decode(it, Base64.DEFAULT)) }

        val hostUrl = "https://" + url.toHttpUrl().host
        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("origin", hostUrl)
            .build()

        runCatching { tryAjaxPost(unpackedScript, hostUrl) }

        val subtitles = doc.select("track[src]").map {
            Track(it.absUrl("src"), it.attr("label").ifEmpty { it.attr("srclang") })
        }

        return listOf(Video(playlistUrl, "Closeload", playlistUrl, videoHeaders, subtitleTracks = subtitles))
    }

    private suspend fun tryAjaxPost(script: String, hostUrl: String) {
        val hash = script.getProperty("hash:")
        val url = script.getProperty("url:").let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "https://" + hostUrl + it
                !it.startsWith("https://") -> "https://$it"
                else -> it
            }
        }

        val body = FormBody.Builder().add("hash", hash).build()

        client.newCall(POST(url, headers, body)).await().close()
    }

    private fun String.getProperty(before: String) =
        substringAfter("$before\"").substringBefore('"')
}

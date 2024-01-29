package eu.kanade.tachiyomi.lib.fastreamextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.internal.commonEmptyHeaders

class FastreamExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    private val videoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$FASTREAM_URL/")
            .set("Origin", FASTREAM_URL)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, videoHeaders) }

    fun videosFromUrl(url: String, prefix: String = "Fastream:", needsSleep: Boolean = true): List<Video> {
        return runCatching {
            val firstDoc = client.newCall(GET(url, videoHeaders)).execute().asJsoup()

            if (needsSleep) Thread.sleep(5100L) // 5s is the minimum

            val scriptElement = if (firstDoc.select("input[name]").any()) {
                val form = FormBody.Builder().apply {
                    firstDoc.select("input[name]").forEach {
                        add(it.attr("name"), it.attr("value"))
                    }
                }.build()
                val doc = client.newCall(POST(url, videoHeaders, body = form)).execute().asJsoup()
                doc.selectFirst("script:containsData(jwplayer):containsData(vplayer)") ?: return emptyList()
            } else {
                firstDoc.selectFirst("script:containsData(jwplayer):containsData(vplayer)") ?: return emptyList()
            }

            val scriptData = scriptElement.data().let {
                when {
                    it.contains("eval(function(") -> JsUnpacker.unpackAndCombine(it)
                    else -> it
                }
            } ?: return emptyList()

            val videoUrl = scriptData.substringAfter("file:\"").substringBefore("\"").trim()

            return when {
                videoUrl.contains(".m3u8") -> {
                    playlistUtils.extractFromHls(videoUrl, videoNameGen = { "$prefix$it" })
                }
                else -> listOf(Video(videoUrl, prefix, videoUrl, videoHeaders))
            }
        }.getOrElse { emptyList() }
    }
}

private const val FASTREAM_URL = "https://fastream.to"

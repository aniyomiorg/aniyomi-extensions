package eu.kanade.tachiyomi.animeextension.pt.pobreflix

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors.EplayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors.MyStreamExtractor
import eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors.SuperFlixExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

class Pobreflix : DooPlay(
    "pt-BR",
    "Pobreflix",
    "https://pobreflix.biz",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.featured div.poster"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/series/page/$page/", headers)

    // ============================ Video Links =============================
    private val superflixExtractor by lazy { SuperFlixExtractor(client, headers, ::genericExtractor) }
    private val eplayerExtractor by lazy { EplayerExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mystreamExtractor by lazy { MyStreamExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }
        return doc.select("div.source-box > a").flatMap {
            runCatching {
                val data = it.attr("href").toHttpUrl().queryParameter("auth")
                    ?.let { Base64.decode(it, Base64.DEFAULT) }
                    ?.let(::String)
                    ?: return@flatMap emptyList()
                val url = data.replace("\\", "").substringAfter("url\":\"").substringBefore('"')
                when {
                    url.contains("superflix") ->
                        superflixExtractor.videosFromUrl(url)
                    else -> genericExtractor(url)
                }
            }.getOrElse { emptyList() }
        }
    }

    private fun genericExtractor(url: String, language: String = ""): List<Video> {
        return when {
            url.contains("filemoon") -> filemoonExtractor.videosFromUrl(url, headers = headers)
            url.contains("watch.brplayer") || url.contains("/watch?v=") ->
                mystreamExtractor.videosFromUrl(url, language)
            url.contains("embedplayer") -> eplayerExtractor.videosFromUrl(url, language)
            else -> emptyList()
        }
    }
}

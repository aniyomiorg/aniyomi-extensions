package eu.kanade.tachiyomi.animeextension.pt.pobreflix

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors.PainelfxExtractor
import eu.kanade.tachiyomi.animesource.model.Video
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
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }
        return doc.select("div.source-box > a").flatMap {
            val data = it.attr("href").toHttpUrl().queryParameter("auth")
                ?.let { Base64.decode(it, Base64.DEFAULT) }
                ?.let(::String)
                ?: return@flatMap emptyList()
            val url = data.replace("\\", "").substringAfter("url\":\"").substringBefore('"')
            when {
                url.contains("painelfx") ->
                    PainelfxExtractor(client).videosFromUrl(url, headers)
                else -> emptyList()
            }
        }
    }
}

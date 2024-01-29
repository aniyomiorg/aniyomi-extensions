package eu.kanade.tachiyomi.animeextension.hi.animesaga

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimeSAGA : DooPlay(
    "hi",
    "AnimeSAGA",
    "https://www.animesaga.in",
) {
    private val videoHost = "https://cdn.animesaga.in"

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.top-imdb-list > div.top-imdb-item"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val playerUrls = response.asJsoup()
            .select("ul#playeroptionsul li:not([id=player-option-trailer])")
            .map(::getPlayerUrl)

        return playerUrls.flatMap { url ->
            runCatching {
                getPlayerVideos(url)
            }.getOrElse { emptyList() }
        }
    }

    private val chillxExtractor by lazy { ChillxExtractor(client, headers) }

    private fun getPlayerVideos(url: String): List<Video> {
        return when {
            videoHost in url -> chillxExtractor.videoFromUrl(url, "$baseUrl/")
            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .let { response ->
                response
                    .body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }
}

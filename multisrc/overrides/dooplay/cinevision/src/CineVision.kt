package eu.kanade.tachiyomi.animeextension.pt.cinevision

import eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors.EmbedflixExtractor
import eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors.VidmolyExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class CineVision : DooPlay(
    "pt-BR",
    "CineVision",
    "https://cinevisionv3.online",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.w_item_b > a"

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.fa-chevron-right"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.flatMap(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
        val url = getPlayerUrl(player)
        return when {
            "vidmoly.to" in url ->
                VidmolyExtractor(client).getVideoList(url, name)
            "streamlare.com" in url ->
                StreamlareExtractor(client).videosFromUrl(url, name)
            "embedflix.in" in url ->
                EmbedflixExtractor(client).videosFromUrl(url)
            else -> emptyList<Video>()
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
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
                    .let { url ->
                        when {
                            url.contains("iframe") -> {
                                url.substringAfter(" src=\"")
                                    .substringBefore("\" ")
                                    .let { "https:$it" }
                            }

                            else -> url
                        }
                    }
            }
    }

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-bars"

    override val genresListMessage = "Categoria"
}

package eu.kanade.tachiyomi.animeextension.pt.cinevision

import eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors.VidmolyExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class CineVision : DooPlay(
    "pt-BR",
    "CineVision",
    "https://cinevision.vc",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.w_item_b > a"

    // =============================== Latest ===============================
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
            else -> emptyList<Video>()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .execute()
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
                    .let { "https:$it" }
            }
    }

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-bars"

    override val genresListMessage = "Categoria"
}

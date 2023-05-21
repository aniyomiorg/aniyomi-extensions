package eu.kanade.tachiyomi.animeextension.pt.animeshouse

import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.EdifierExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.EmbedExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.GenericExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.JsUnpacker
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.McpExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.MpFourDooExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.RedplayBypasser
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimesHouse : DooPlay(
    "pt-BR",
    "Animes House",
    "https://animeshouse.net",
) {
    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.icon-chevron-right"

    // ============================ Video Links =============================
    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()
        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .use { it.asJsoup().selectFirst("iframe")!!.attr("src") }
            .let {
                if (it.startsWith("/redplay")) {
                    RedplayBypasser(client, headers).fromUrl(baseUrl + it)
                } else {
                    it
                }
            }
    }

    override fun videoListParse(response: Response): List<Video> {
        val players = response.use { it.asJsoup().select("ul#playeroptionsul li") }
        return players.flatMap { player ->
            runCatching {
                val url = getPlayerUrl(player)
                getPlayerVideos(url)
            }.getOrDefault(emptyList<Video>())
        }
    }

    private fun getPlayerVideos(url: String): List<Video> {
        val iframeBody = client.newCall(GET(url, headers)).execute()
            .use { it.body.string() }

        val unpackedBody = JsUnpacker.unpack(iframeBody)

        return when {
            "embed.php?" in url ->
                EmbedExtractor(headers).getVideoList(url, iframeBody)
            "edifier" in url ->
                EdifierExtractor(client, headers).getVideoList(url)
            "mp4doo" in url ->
                MpFourDooExtractor(client, headers).getVideoList(unpackedBody)
            "clp-new" in url || "gcloud" in url ->
                GenericExtractor(client, headers).getVideoList(url, unpackedBody)
            "mcp_comm" in unpackedBody ->
                McpExtractor(client, headers).getVideoList(unpackedBody)
            else -> emptyList<Video>()
        }
    }

    // ============================== Settings ==============================
    override val prefQualityEntries = arrayOf(
        "SD - 240p",
        "SD - 360p",
        "SD - 480p",
        "HD - 720p",
        "FULLHD - 1080p",
    )

    override val prefQualityValues = arrayOf("240p", "360p", "480p", "720p", "1080p")

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.icon-bars"
}

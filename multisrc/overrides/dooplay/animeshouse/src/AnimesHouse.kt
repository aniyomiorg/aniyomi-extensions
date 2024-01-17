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
    // This source does not have a "popular" animes page, so we're going to
    // use latest updates page instead.
    override suspend fun getPopularAnime(page: Int) = getLatestUpdates(page)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.icon-chevron-right"

    // ============================ Video Links =============================
    private val redplayBypasser by lazy { RedplayBypasser(client, headers) }

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
                when {
                    it.contains("/redplay") -> {
                        val url = if (it.startsWith("/")) baseUrl + it else it
                        redplayBypasser.fromUrl(url)
                    }
                    else -> it
                }
            }
    }

    override fun videoListParse(response: Response): List<Video> {
        val players = response.use { it.asJsoup().select("ul#playeroptionsul li") }
        return players.flatMap { player ->
            runCatching {
                val url = getPlayerUrl(player)
                getPlayerVideos(url)
            }.getOrElse { emptyList<Video>() }
        }
    }

    private val embedExtractor by lazy { EmbedExtractor(headers) }
    private val edifierExtractor by lazy { EdifierExtractor(client, headers) }
    private val mp4dooExtractor by lazy { MpFourDooExtractor(client, headers) }
    private val genericExtractor by lazy { GenericExtractor(client, headers) }
    private val mcpExtractor by lazy { McpExtractor(client, headers) }

    private fun getPlayerVideos(url: String): List<Video> {
        val iframeBody = client.newCall(GET(url, headers)).execute()
            .use { it.body.string() }

        val unpackedBody = JsUnpacker.unpack(iframeBody)

        return when {
            "embed.php?" in url -> embedExtractor.getVideoList(url, iframeBody)
            "edifier" in url -> edifierExtractor.getVideoList(url)
            "mp4doo" in url || "doomp4" in url -> mp4dooExtractor.getVideoList(unpackedBody)
            "clp-new" in url || "gcloud" in url -> genericExtractor.getVideoList(url, unpackedBody)
            "mcp_comm" in unpackedBody -> mcpExtractor.getVideoList(unpackedBody)
            "cloudg" in url -> {
                unpackedBody.substringAfter("sources:[").substringBefore(']')
                    .split('{')
                    .drop(1)
                    .mapNotNull {
                        val videoUrl = it.substringAfter("\"file\":\"").substringBefore('"')
                            .takeUnless(String::isBlank) ?: return@mapNotNull null
                        val label = it.substringAfter("\"label\":\"").substringBefore('"')
                        Video(videoUrl, "CloudG - $label", videoUrl, headers)
                    }
            }
            else -> emptyList()
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

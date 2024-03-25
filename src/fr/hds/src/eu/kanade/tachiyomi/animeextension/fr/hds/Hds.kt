package eu.kanade.tachiyomi.animeextension.fr.hds

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

class Hds : DooPlay(
    "fr",
    "HDS",
    "https://www.hds.quest",
) {

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendance/page/$page/", headers)

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = "#nextpagination"

    // =============================== Latest ===============================
    override val supportsLatest = false

    // =============================== Search ===============================
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun genresListSelector() = ".genres.scrolling li a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = super.animeDetailsParse(document).apply {
        if (document.select(".dt-breadcrumb li:nth-child(2)").text() == "Films") {
            status = SAnime.COMPLETED
        }
    }

    // ============================ Video Links =============================
    @Serializable
    data class VideoLinkDTO(@SerialName("embed_url") val url: String)

    private val fileMoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("#playeroptions li:not(#player-option-trailer)")
        return players.parallelCatchingFlatMapBlocking { it ->
            val post = it.attr("data-post")
            val nume = it.attr("data-nume")
            val type = it.attr("data-type")
            val raw = client.newCall(GET("$baseUrl/wp-json/dooplayer/v1/post/$post?type=$type&source=$nume", headers))
                .execute()
                .body.string()
            val securedUrl = json.decodeFromString<VideoLinkDTO>(raw).url
            val playerUrl = client.newCall(GET(securedUrl, headers)).execute().use { it.request.url.toString() }
            when {
                playerUrl.contains("sentinel") -> fileMoonExtractor.videosFromUrl(playerUrl)
                playerUrl.contains("hdsplay.online") -> streamHideVidExtractor.videosFromUrl(playerUrl)
                else -> emptyList()
            }
        }
    }
}

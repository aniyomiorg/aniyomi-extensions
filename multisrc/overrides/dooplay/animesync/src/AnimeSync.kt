package eu.kanade.tachiyomi.animeextension.pt.animesync

import eu.kanade.tachiyomi.animeextension.pt.animesync.extractors.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimeSync : DooPlay(
    "pt-BR",
    "AnimeSync",
    "https://animesync.org",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.imdbRating > article > a"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes")

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div.items > article.item"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/generos/")
    override fun genresListSelector() = "ul.generos li > a"

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("360p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelMap {
            runCatching { getPlayerVideos(it) }.getOrElse { emptyList() }
        }.flatten()
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = getPlayerUrl(player)
        return when {
            url.contains("/jwplayer/") -> {
                val doc = client.newCall(GET(url, headers)).execute()
                    .use { it.asJsoup() }
                val source = doc.selectFirst("source") ?: return emptyList()
                val quality = source.attr("size").ifEmpty { "360" } + "p"
                val videoUrl = source.attr("src")
                listOf(Video(videoUrl, "JWPlayer - $quality", videoUrl, headers))
            }

            url.contains("/player2/") -> {
                val doc = client.newCall(GET(url, headers)).execute()
                    .use { it.asJsoup() }
                doc.selectFirst("script:containsData(sources:)")
                    ?.data()
                    ?.substringAfter("sources: [")
                    ?.substringBefore("]")
                    ?.split("{")
                    ?.drop(1)
                    .orEmpty()
                    .map {
                        val quality = it.substringAfter("label\":\"").substringBefore('"')
                        val videoUrl = it.substringAfter("file\":\"").substringBefore('"')
                        Video(videoUrl, "Player 2 - $quality", videoUrl, headers)
                    }
            }

            url.contains("/player/") -> {
                BloggerExtractor(client).videosFromUrl(url, headers)
            }

            url.contains("csst.online") -> {
                val doc = client.newCall(GET(url, headers)).execute()
                    .use { it.asJsoup() }
                doc.selectFirst("script:containsData(isMobile):containsData(file:)")
                    ?.data()
                    ?.substringAfter("file:\"")
                    ?.substringBefore('"')
                    ?.split(",")
                    .orEmpty()
                    .map {
                        val quality = it.substringAfter("[").substringBefore("]")
                        val videoUrl = it.substringAfter("]")
                        val videoHeaders = Headers.Builder().add("Referer", videoUrl).build()
                        Video(videoUrl, "CSST - $quality", videoUrl, videoHeaders)
                    }
            }

            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .execute()
            .body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // ============================= Utilities ==============================
    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}

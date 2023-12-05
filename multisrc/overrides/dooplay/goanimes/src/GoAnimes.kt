package eu.kanade.tachiyomi.animeextension.pt.goanimes

import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.BloggerJWPlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.GoAnimesExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.JsDecoder
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.LinkfunBypasser
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.PlaylistExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import org.jsoup.nodes.Element

class GoAnimes : DooPlay(
    "pt-BR",
    "GoAnimes",
    "https://goanimes.net",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#featured-titles article.item.tvshows > div.poster"

    // =============================== Latest ===============================
    override val latestUpdatesPath = "lancamentos"

    // ============================== Episodes ==============================
    override val seasonListSelector = "div#seasons > *"

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        // All episodes are listed under a single page
        season.selectFirst(episodeListSelector())?.let {
            return getSeasonEpisodesRecursive(season)
        }

        // Episodes are listed at another page
        val url = season.attr("href")
        return client.newCall(GET(url, headers))
            .execute()
            .use { it.asJsoup() }
            .let(::getSeasonEpisodes)
    }

    private val episodeListNextPageSelector = "div.pagination span.current + a:not(.arrow_pag)"

    private fun getSeasonEpisodesRecursive(season: Element): List<SEpisode> {
        var doc = season.root()
        return buildList {
            do {
                if (isNotEmpty()) {
                    doc.selectFirst(episodeListNextPageSelector)?.let {
                        val url = it.attr("abs:href")
                        doc = client.newCall(GET(url, headers)).execute()
                            .use { it.asJsoup() }
                    }
                }
                addAll(super.getSeasonEpisodes(doc))
            } while (doc.selectFirst(episodeListNextPageSelector) != null)
            reversed()
        }
    }

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("240p", "360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    private val goanimesExtractor by lazy { GoAnimesExtractor(client, headers) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val linkfunBypasser by lazy { LinkfunBypasser(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }
        val players = document.select("ul#playeroptionsul li")
        return players.parallelMap {
            runCatching {
                getPlayerVideos(it)
            }.getOrElse { emptyList() }
        }.flatten().ifEmpty { throw Exception("Nenhum vídeo encontrado.") }
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = getPlayerUrl(player)
        return when {
            "player5.goanimes.net" in url -> goanimesExtractor.videosFromUrl(url)
            "https://gojopoolt" in url -> {
                val headers = headers.newBuilder()
                    .set("referer", url)
                    .build()

                val script = client.newCall(GET(url, headers)).execute()
                    .use { it.body.string() }
                    .let { JsDecoder.decodeScript(it, false) }

                script.substringAfter("sources: [")
                    .substringBefore(']')
                    .split('{')
                    .drop(1)
                    .mapNotNull {
                        val videoUrl = it.substringAfter("file: ")
                            .substringBefore(", ")
                            .trim('"', '\'', ' ')
                            .ifBlank { return@mapNotNull null }

                        val resolution = it.substringAfter("label: ", "")
                            .substringAfter('"')
                            .substringBefore('"')
                            .ifBlank { "Default" }

                        Video(videoUrl, "Gojopoolt - $resolution", videoUrl, headers)
                    }
            }
            listOf("/bloggerjwplayer", "/m3u8", "/multivideo").any { it in url } -> {
                val script = client.newCall(GET(url)).execute()
                    .use { it.body.string() }
                    .let(JsDecoder::decodeScript)
                when {
                    "/bloggerjwplayer" in url ->
                        BloggerJWPlayerExtractor.videosFromScript(script)
                    "/m3u8" in url ->
                        PlaylistExtractor.videosFromScript(script)
                    "/multivideo" in url ->
                        script.substringAfter("attr")
                            .substringAfter(" \"")
                            .substringBefore('"')
                            .let(goanimesExtractor::videosFromUrl)
                    else -> emptyList<Video>()
                }
            }
            "www.blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            else -> emptyList<Video>()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        val url = client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .execute()
            .use { it.body.string() }
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")

        return when {
            "/protetorlinks/" in url -> {
                val link = client.newCall(GET(url)).execute()
                    .use { it.asJsoup() }
                    .selectFirst("a[href]")!!.attr("href")

                client.newCall(GET(link)).execute()
                    .use(linkfunBypasser::getIframeUrl)
            }
            else -> url
        }
    }

    // ============================= Utilities ==============================
    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}

package eu.kanade.tachiyomi.animeextension.pt.goanimes

import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.BloggerJWPlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.GoAnimesExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.JsDecoder
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.LinkfunBypasser
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.PlaylistExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
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
        return client.newCall(GET(url))
            .execute()
            .asJsoup()
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
    private val linkfunBypasser by lazy { LinkfunBypasser(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.flatMap(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = getPlayerUrl(player)
        return when {
            "player5.goanimes.net" in url -> goanimesExtractor.videosFromUrl(url)
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
}

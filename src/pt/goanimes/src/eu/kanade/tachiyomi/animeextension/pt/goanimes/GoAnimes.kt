package eu.kanade.tachiyomi.animeextension.pt.goanimes

import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.BloggerJWPlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.GoAnimesExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.JsDecoder
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.LinkfunBypasser
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.PlaylistExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
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
                            .asJsoup()
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
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private suspend fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
            .replace("FULLHD", "1080p")
            .replace("HD", "720p")
            .replace("SD", "480p")
        val url = getPlayerUrl(player)
        return when {
            "https://gojopoolt" in url -> {
                val headers = headers.newBuilder()
                    .set("referer", url)
                    .build()

                val script = client.newCall(GET(url, headers)).await()
                    .body.string()
                    .let { JsDecoder.decodeScript(it, false).ifBlank { it } }

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
                            .ifBlank { name.split('-').last().trim() }

                        val partialName = name.split('-').first().trim()
                        return when {
                            videoUrl.contains(".m3u8") -> {
                                playlistUtils.extractFromHls(
                                    videoUrl,
                                    url,
                                    videoNameGen = {
                                        "$partialName - ${it.replace("Video", resolution)}"
                                    },
                                )
                            }
                            else -> listOf(Video(videoUrl, "$partialName - $resolution", videoUrl, headers))
                        }
                    }
            }
            listOf("/bloggerjwplayer", "/m3u8", "/multivideo").any { it in url } -> {
                val script = client.newCall(GET(url)).await()
                    .body.string()
                    .let { JsDecoder.decodeScript(it, true).ifBlank { JsDecoder.decodeScript(it, false).ifBlank { it } } }
                when {
                    "/bloggerjwplayer" in url ->
                        BloggerJWPlayerExtractor.videosFromScript(script)
                    "/m3u8" in url ->
                        PlaylistExtractor.videosFromScript(script)
                    "/multivideo" in url ->
                        script.substringAfter("attr")
                            .substringAfter(" \"")
                            .substringBefore('"')
                            .let { goanimesExtractor.videosFromUrl(it, name) }

                    else -> emptyList<Video>()
                }
            }
            "www.blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            else -> goanimesExtractor.videosFromUrl(url, name)
        }
    }

    private suspend fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        val url = client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .await()
            .body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")

        return when {
            "/protetorlinks/" in url -> {
                val link = client.newCall(GET(url)).await()
                    .asJsoup()
                    .selectFirst("a[href]")!!.attr("href")

                client.newCall(GET(link)).await()
                    .use(linkfunBypasser::getIframeUrl)
            }
            else -> url
        }
    }
    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}

package eu.kanade.tachiyomi.animeextension.pt.goanimes

import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.BloggerJWPlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.GoAnimesExtractor
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.JsDecoder
import eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors.PlaylistExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
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

    // ============================== Episodes ==============================
    override val seasonListSelector = "div#seasons > *"

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        // All episodes are listed under a single page
        season.selectFirst(episodeListSelector())?.let {
            return super.getSeasonEpisodes(season)
        }

        // Episodes are listed at another page
        val url = season.attr("href")
        return client.newCall(GET(url))
            .execute()
            .asJsoup()
            .let { super.getSeasonEpisodes(it) }
    }

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("240p", "360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.flatMap(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = getPlayerUrl(player)
        return when {
            "player5.goanimes.net" in url ->
                GoAnimesExtractor(client).videosFromUrl(url)
            "/v/" in url ->
                runCatching {
                    FembedExtractor(client).videosFromUrl(url)
                }.getOrDefault(emptyList<Video>())
            listOf("/bloggerjwplayer", "/m3u8", "/multivideo").any { it in url } -> {
                val script = client.newCall(GET(url)).execute()
                    .body.string()
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
                            .let { GoAnimesExtractor(client).videosFromUrl(it) }
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
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .execute()
            .body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // =============================== Latest ===============================
    override val latestUpdatesPath = "lancamentos"
}

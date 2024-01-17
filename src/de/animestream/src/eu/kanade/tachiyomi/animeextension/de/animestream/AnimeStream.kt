package eu.kanade.tachiyomi.animeextension.de.animestream

import eu.kanade.tachiyomi.animeextension.de.animestream.extractors.MetaExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class AnimeStream : ParsedAnimeHttpSource() {

    override val name = "Anime-Stream"

    override val baseUrl = "https://anime-stream.to"

    override val lang = "de"

    override val id: Long = 314593699490737069

    override val supportsLatest = false

    override fun popularAnimeSelector(): String = "div.movies-list div.ml-item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("a img").attr("data-original")
        anime.title = element.select("a img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.active ~ li"

    // episodes

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episodeElement = document.select("div.les-content a")
        episodeElement.forEach {
            var num = 0
            val episode = nEpisodeFromElement(it, num)
            episodeList.add(episode)
        }

        return episodeList.reversed()
    }

    private fun nEpisodeFromElement(element: Element, num: Int): SEpisode {
        num + 1
        val episode = SEpisode.create()
        episode.episode_number = num.toFloat()
        episode.name = element.text()
        episode.setUrlWithoutDomain(element.attr("href"))
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val url = document.select("div a.lnk-lnk").attr("href")
        val quality = "Metastream"
        val video = MetaExtractor(client).videoFromUrl(url, quality)
        if (video != null) {
            videoList.add(video)
        }
        return videoList.reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("a img").attr("data-original")
        anime.title = element.select("a img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "li.active ~ li"

    override fun searchAnimeSelector(): String = "div.movies-list div.ml-item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.thumb img").attr("src")
        anime.title = document.select("div.thumb img").attr("alt")
        anime.description = document.select("div.desc p.f-desc").text()
        anime.status = parseStatus(document.select("div.mvici-right span[itemprop=\"duration\"]").text())
        anime.genre = document.select("div.mvici-left p a[rel=\"category tag\"]").joinToString(", ") { it.text() }
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Abgeschlossen", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
}

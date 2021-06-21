package eu.kanade.tachiyomi.animeextension.en.gogoanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class GogoAnime : ParsedAnimeHttpSource() {

    override val name = "Gogoanime"

    override val baseUrl = "https://www1.gogoanime.ai"

    override val lang = "en"

    override val supportsLatest = true

    override fun popularAnimeSelector(): String = "div.img a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular.html?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val totalEpisodes = document.select(episodeListSelector()).last().attr("ep_end")
        val id = document.select("input#movie_id").attr("value")
        return runBlocking { episodesRequest(totalEpisodes, id) }
    }

    private suspend fun episodesRequest(totalEpisodes: String, id: String): List<SEpisode> {
        val request = GET("https://ajax.gogo-load.com/ajax/load-list-episode?ep_start=0&ep_end=$totalEpisodes&id=$id")
        val epResponse = client.newCall(request)
            .await()
        val document = epResponse.asJsoup()
        return document.select("a").map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.attr("href").substringAfter(" "))
        val ep = element.selectFirst("div.name").ownText().substringAfter(" ")
        episode.episode_number = ep.toFloat()
        episode.name = "Episode $ep"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun videoListSelector() = "div.mirror_link a[download]"

    override fun videoFromElement(element: Element): Video {
        val quality = element.text().substringAfter("Download (").replace("P - mp4)", "p")
        return Video(element.attr("href"), quality, element.attr("href"), null)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun searchAnimeSelector(): String = "div.img a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search.html?keyword=$query&page=$page")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.anime_info_body_bg h1").text()
        anime.genre = document.select("p.type:eq(5) a").joinToString("") { it.text() }
        anime.description = document.select("p.type:eq(4)").first().ownText()
        anime.status = parseStatus(document.select("p.type:eq(7) a").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.attr("href"))
        val style = element.select("div.thumbnail-popular").attr("style")
        anime.thumbnail_url = style.substringAfter("background: url('").substringBefore("');")
        anime.title = element.attr("title")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("https://ajax.gogo-load.com/ajax/page-recent-release-ongoing.html?page=$page&type=1")

    override fun latestUpdatesSelector(): String = "div.added_series_body.popular li a:has(div)"

    override fun videoUrlFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlSelector() = throw Exception("not used")
}

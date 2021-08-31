package eu.kanade.tachiyomi.animeextension.ar.witanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class WitAnime : ParsedAnimeHttpSource() {

    override val name = "WIT ANIME"

    override val baseUrl = "https://witanime.com"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularAnimeSelector(): String = "div.anime-list-content div:nth-child(1) div.col-lg-2 div.anime-card-container"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/قائمة-الانمي/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").first().attr("abs:src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination a.next"

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.ehover6 > div.episodes-card-title > h3"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.name = element.select("a").text()
        val episodeNumberString = element.select("a").text().removePrefix("الحلقة ").removePrefix("الخاصة ").removePrefix("الأونا ").removePrefix("الفلم ").removePrefix("الأوفا ")
        episode.episode_number = episodeNumberString.toFloat()
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("ul.nav-tabs li a[id^=WitAnime-4shared]").attr("abs:data-ep-url")
        val referer = response.request.url.encodedPath
        val newHeaderList = mutableMapOf(Pair("referer", baseUrl + referer))
        headers.forEach { newHeaderList[it.first] = it.second }
        val iframeResponse = runBlocking {
            client.newCall(GET(iframe, newHeaderList.toHeaders()))
                .await().asJsoup()
        }
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        element.attr("src")
        return Video(element.attr("src"), "Unknown quality", element.attr("src"), null)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").first().attr("abs:src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination a.next"

    override fun searchAnimeSelector(): String = "div.anime-list-content div:nth-child(1) div.col-lg-2 div.anime-card-container"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/?search_param=animes&s=$query")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.thumbnail").first().attr("src")
        anime.title = document.select("h1.anime-details-title").text()
        anime.genre = document.select("ul.anime-genres > li > a, div.anime-info > a").joinToString(", ") { it.text() }
        anime.description = document.select("p.anime-story").text()
        document.select("div.anime-info a").text()?.also { statusText ->
            when {
                statusText.contains("يعرض الان", true) -> anime.status = SAnime.ONGOING
                statusText.contains("مكتمل", true) -> anime.status = SAnime.COMPLETED
                else -> anime.status = SAnime.UNKNOWN
            }
        }

        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")
}

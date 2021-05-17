package eu.kanade.tachiyomi.animeextension.en.tenshimoe

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.online.ParsedAnimeHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Float.parseFloat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

class TenshiMoe : ParsedAnimeHttpSource() {

    override val name = "tenshi.moe"

    override val baseUrl = "https://tenshi.moe"

    override val lang = "en"

    override val supportsLatest = false

    override fun popularAnimeSelector(): String = "ul.anime-loop.loop li a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime?s=vdy-d&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div span").not(".badge").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = "ul.pagination li.page-item a[rel=next]"

    override fun episodeListSelector() = "ul.episode-loop li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        val episodeNumberString = element.select("div.episode-number").text().removePrefix("Episode ")
        var numeric = true
        try {
            val num = parseFloat(episodeNumberString)
        } catch (e: NumberFormatException) {
            numeric = false
        }
        episode.episode_number = if (numeric) episodeNumberString.toFloat() else element.parent().className().removePrefix("episode").toFloat()
        episode.name = element.select("div.episode-number").text() + ": " + element.select("div.episode-label").text() + element.select("div.episode-title").text()
        return episode
    }

    override fun episodeLinkSelector() = "source"

    override fun linkFromElement(element: Element): String {
        return element.attr("src")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div span").not("div span.badge").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "ul.anime-loop.loop li a"

    override fun searchAnimeRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/anime?q=$query")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.cover-image.img-thumbnail").first().attr("src")
        anime.title = document.select("h1.mb-3").text()
        anime.genre = document.select("li.genre span.value").joinToString(", ") { it.text() }
        anime.description = document.select("div.card-body").text()
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")
}

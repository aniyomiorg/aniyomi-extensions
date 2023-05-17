package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Tokuzilla : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Tokuzilla"

    override val baseUrl = "https://tokuzilla.net"

    override val lang = "en"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div.col-sm-4.col-xs-12.item.post"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/page/$page")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href").replace("https://tokuzilla.net", ""))
        anime.thumbnail_url = element.selectFirst("img").attr("src")
        anime.title = element.selectFirst("a").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    // ============================== Episodes ==============================

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val infoElement = document.selectFirst("ul.pagination.post-tape")
        Log.i("HAELPU", infoElement.html())
        if (infoElement != null) {
            infoElement.html().split("<a href=\"").drop(1).forEach {
                val link = it.substringBefore("\"")
                val episodeNumber = it.substringBefore("</a>").substringAfter(">")
                val episode = SEpisode.create()
                episode.setUrlWithoutDomain(link)
                episode.episode_number = episodeNumber.toFloat()
                episode.name = "Episode $episodeNumber"
                episodeList.add(episode)
            }
        } else {
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val frameLink = document.selectFirst("iframe[id=frame]").attr("src")
        Log.i("SDFSDF", frameLink.substringBefore("/v/"))
        val videos = ChillxExtractor(client, frameLink.substringBefore("/v/")).videoFromUrl(frameLink)
        if (videos != null) {
            videoList.addAll(videos)
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        return this
    }

    override fun videoListSelector() = throw Exception("c")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "link[rel=next]"

    override fun searchAnimeSelector(): String = "div.col-sm-4.col-xs-12.item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/?s=$query")

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("HAHAHAHA"),
    )

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create().apply {
            genre = parseGenres(document.selectFirst("table").html())
            description = document.selectFirst("p").text()
            author = parseYear(document.selectFirst("table").html())
            status = parseStatus(document.selectFirst("table").text())
        }
        return anime
    }

    private fun parseGenres(genreString: String): String {
        val genres = buildString {
            genreString.split("<a href=").drop(1).dropLast(1).forEach {
                append(it.substringAfter("rel=\"tag\">").substringBefore("</a>"))
                append(", ")
            }
        }
        return genres.dropLast(2)
    }

    private fun parseYear(yearString: String): String {
        return "Year " + yearString.substringAfter("Year").substring(47, 51)
    }

    private fun parseStatus(statusString: String): Int {
        return if (statusString.contains("Ongoing")) SAnime.ONGOING
        else SAnime.COMPLETED
    }

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    override fun latestUpdatesSelector(): String = throw Exception("not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")

    // ============================= Preference =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        TODO("Not yet implemented")
    }
}

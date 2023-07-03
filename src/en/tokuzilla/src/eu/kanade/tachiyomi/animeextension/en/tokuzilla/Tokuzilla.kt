package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
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
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").replace("https://tokuzilla.net", ""))
        anime.thumbnail_url = element.selectFirst("img")!!.attr("src")
        anime.title = element.selectFirst("a")!!.attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    // ============================== Episodes ==============================

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val infoElement = document.selectFirst("ul.pagination.post-tape")
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
            val link = document.selectFirst("meta[property=og:url]")!!.attr("content")
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(link)
            episode.episode_number = 1F
            episode.name = "Movie"
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val frameLink = document.selectFirst("iframe[id=frame]")!!.attr("src")
        val videos = ChillxExtractor(client, headers).videoFromUrl(frameLink, baseUrl)
        if (videos != null) {
            videoList.addAll(videos)
        }
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun searchAnimeSelector(): String = "div.col-sm-4.col-xs-12.item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var url = baseUrl
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> url += filter.toUriPart()
                else -> {}
            }
        }
        return GET("$url/page/$page?s=$query")
    }

    override fun getFilterList() = AnimeFilterList(
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("Any", ""),
            Pair("Series", "/series"),
            Pair("Movie", "/movie"),
            Pair("Kamen Rider", "/kamen-rider"),
            Pair("Super Sentai", "/super-sentai"),
            Pair("Armor Hero", "/armor-hero"),
            Pair("Garo", "/garo"),
            Pair("Godzilla", "/godzilla"),
            Pair("Metal Heroes", "/metal-heroes"),
            Pair("Power Rangers", "/power-ranger"),
            Pair("Ultraman", "/ultraman"),
            Pair("Other", "/other"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create().apply {
            genre = parseGenres(document.selectFirst("table")!!.html())
            description = document.selectFirst("p")!!.text()
            author = parseYear(document.selectFirst("table")!!.html())
            status = parseStatus(document.selectFirst("table")!!.text())
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
        return if (statusString.contains("Ongoing")) {
            SAnime.ONGOING
        } else {
            SAnime.COMPLETED
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector(): String = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    override fun latestUpdatesSelector(): String = throw Exception("not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")

    // ============================= Preference =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}

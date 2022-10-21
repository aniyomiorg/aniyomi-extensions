package eu.kanade.tachiyomi.animeextension.de.anime24

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class Anime24 : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime24"

    override val baseUrl = "https://anime24.to"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "article.bs"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=&type=&order=popular")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.tip").attr("href"))
        anime.thumbnail_url = element.select("div.limit img").attr("src")
        anime.title = element.select("div.limit img").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.r"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        if (!document.select("div.eplister ul li a div.epl-num").text().contains("Movie")) {
            val episodeElement = document.select("div.eplister ul")
            val episode = parseEpisodesFromSeries(episodeElement)
            episodeList.addAll(episode)
        } else {
            val episode = SEpisode.create()
            episode.name = document.select("div.infox h1").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(document.select("div.eplister ul li a").attr("href"))
            episodeList.add(episode)
        }
        return episodeList
    }

    private fun parseEpisodesFromSeries(element: Elements): List<SEpisode> {
        val episodeElements = element.select("li")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("a div.epl-num").text().toFloat()
        val folge = element.select("a div.epl-num").text()
        episode.name = "Folge $folge : " + element.select("a div.epl-title").text()
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val linklazy = document.select("#pembed iframe[data-lazy-src]").attr("data-lazy-src")
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("stape", "voe"))
        when {
            linklazy.contains("https://streamtape") || linklazy.contains("https://adblockeronstape") && hosterSelection?.contains("stape") == true -> {
                val quality = "Streamtape"
                val video = StreamTapeExtractor(client).videoFromUrl(linklazy, quality)
                if (video != null) {
                    videoList.add(video)
                }
            }
            linklazy.contains("https://voe.sx") && hosterSelection?.contains("voe") == true -> {
                val quality = "Voe"
                val video = VoeExtractor(client).videoFromUrl(linklazy, quality)
                if (video != null) {
                    videoList.add(video)
                }
            }
        }
        val linksrc = document.select("#pembed iframe").attr("src")
        when {
            linksrc.contains("https://streamtape") || linksrc.contains("https://adblockeronstape") && hosterSelection?.contains("stape") == true -> {
                val quality = "Streamtape"
                val video = StreamTapeExtractor(client).videoFromUrl(linksrc, quality)
                if (video != null) {
                    videoList.add(video)
                }
            }
            linksrc.contains("https://voe.sx") && hosterSelection?.contains("voe") == true -> {
                val quality = "Voe"
                val video = VoeExtractor(client).videoFromUrl(linksrc, quality)
                if (video != null) {
                    videoList.add(video)
                }
            }
        }
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", null)
        if (hoster != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(hoster)) {
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

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.tip").attr("href"))
        anime.thumbnail_url = element.select("div.limit img").attr("src")
        anime.title = element.select("div.limit img").attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.next"

    override fun searchAnimeSelector(): String = "article.bs"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.thumb img").attr("data-lazy-src")
        anime.title = document.select("div.infox h1").text()
        anime.genre = document.select("div.genxed a").joinToString(", ") { it.text() }
        anime.description = document.select("div.entry-content p").text()
        anime.author = document.select("span.split a").joinToString(", ") { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Streamtape", "Voe")
            entryValues = arrayOf("https://streamtape", "https://voe.sx")
            setDefaultValue("https://streamtape")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Hoster auswählen"
            entries = arrayOf("Streamtape", "Voe")
            entryValues = arrayOf("stape", "voe")
            setDefaultValue(setOf("stape", "voe"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}

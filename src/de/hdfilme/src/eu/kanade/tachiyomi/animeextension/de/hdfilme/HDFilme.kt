package eu.kanade.tachiyomi.animeextension.de.hdfilme

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.hdfilme.extractors.MixDropExtractor
import eu.kanade.tachiyomi.animeextension.de.hdfilme.extractors.VudeoExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class HDFilme : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HDFilme"

    override val baseUrl = "https://www.hdfilme.fun"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#dle-content div.short"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filmestreamen/page-$page.html")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.short-img").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("a.short-img img ").attr("src")
        anime.title = element.select("a.short-img img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "span.pnext a"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = document.select("link[rel=\"canonical\"]").attr("href")
        val episodeList = mutableListOf<SEpisode>()
        if (url.contains("serienstreamen")) {
            val episodeElement = document.select("div.seasontab div[align=\"center\"] a")
            episodeElement.forEach {
                val episodes = parseEpisodesFromSeries(it)
                episodeList.addAll(episodes)
            }
        } else {
            val episode = SEpisode.create()
            episode.episode_number = 1F
            episode.name = "Film"
            episode.setUrlWithoutDomain(document.select("link[rel=\"canonical\"]").attr("href"))
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonurl = element.attr("href")
        val episodesHtml = client.newCall(GET(seasonurl)).execute().asJsoup()
        val episodeElements = episodesHtml.select("div.seasontab div[align=\"center\"] a")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("span.number").text().toFloat()
        episode.name = element.attr("title")
        episode.setUrlWithoutDomain(element.attr("href"))
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("dood", "vud", "mix"))
        document.select("div.undervideo li").forEach {
            val url = it.select("div.lien").attr("data-url")
            when {
                url.contains("https://dood") && hosterSelection?.contains("dood") == true -> {
                    val quality = "Doodstream"
                    val video = DoodExtractor(client).videoFromUrl(url, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                url.contains("https://vudeo") && hosterSelection?.contains("vud") == true -> {
                    val video = VudeoExtractor(client).videosFromUrl(url)
                    videoList.addAll(video)
                }
                url.contains("https://mixdrop") && hosterSelection?.contains("mix") == true -> {
                    val video = MixDropExtractor(client).videoFromUrl(url)
                    videoList.addAll(video)
                }
            }
        }
        return videoList
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
        anime.setUrlWithoutDomain(element.select("a.short-img").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("a.short-img img ").attr("src")
        anime.title = element.select("a.short-img img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "span.pnext a"

    override fun searchAnimeSelector(): String = "#dle-content div.short"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/recherche?q=$query&page=$page")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.select("div.movie-page div.mimg img").attr("src")
        anime.title = document.select("meta[property=\"og:title\"]").attr("content")
        anime.description = document.select("meta[property=\"og:title\"]").attr("content")
        document.select("div.short-info div.short-info").forEach { el ->
            if (el.text().contains("Schauspieler:")) {
                anime.author = el.text().replace("Schauspieler:", "").split(" , ").joinToString(", ") { it }
            }
        }
        document.select("div.short-info div.short-info").forEach { el ->
            if (el.text().contains("Genre:")) {
                anime.genre = el.select("a").joinToString(", ") { it.text() }
            }
        }
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
            entries = arrayOf("Doodstream", "Vudeo", "Mixdrop")
            entryValues = arrayOf("https://dood", "https://vudeo", "https://mixdrop")
            setDefaultValue("https://dood")
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
            entries = arrayOf("Doodstream", "Vudeo", "MixDrop")
            entryValues = arrayOf("dood", "vud", "mix")
            setDefaultValue(setOf("dood", "vud", "mix"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}

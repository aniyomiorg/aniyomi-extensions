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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class Anime24 : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime24"

    override val baseUrl = "https://anime24.to"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div#blog-entries article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.post-thumbnail a").attr("href"))
        anime.thumbnail_url = element.select("div.post-thumbnail a img ").attr("data-lazy-src")
        anime.title = element.select("div.blog-entry-content h2.entry-title a").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nav-links a.next"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episodeElement = document.select("div.entry-content div.su-spoiler")
        episodeElement.forEach {
            val episode = episodeFromElement(it)
            episodeList.add(episode)
        }

        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("div.su-spoiler-title").text()
            .substringAfter("Episode ").substringBefore(" ").toFloat()
        episode.name = element.select("div.su-spoiler-title").text()
        episode.url = (element.select("div.su-spoiler-content center iframe").attr("data-lazy-src"))
        return episode
    }

    // Video Extractor

    override fun videoListRequest(episode: SEpisode): Request {
        val url = episode.url.replace(baseUrl, "")
        return GET(url)
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return videosFromElement(url)
    }

    private fun videosFromElement(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("stape", "voe"))
        when {
            url.contains("https://streamtape") || url.contains("https://adblockeronstape") && hosterSelection?.contains("stape") == true -> {
                val quality = "Streamtape"
                val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                if (video != null) {
                    videoList.add(video)
                }
            }
            url.contains("https://voe.sx") || url.contains("https://20demidistance9elongations.com") || url.contains("https://telyn610zoanthropy.com") && hosterSelection?.contains("voe") == true -> {
                val quality = "Voe"
                val video = VoeExtractor(client).videoFromUrl(url, quality)
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
        anime.setUrlWithoutDomain(element.select("div.post-thumbnail a").attr("href"))
        anime.thumbnail_url = element.select("div.post-thumbnail a img ").attr("src")
        anime.title = element.select("div.blog-entry-content h2.entry-title a").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.nav-links a.next"

    override fun searchAnimeSelector(): String = "div#blog-entries article"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.su-column-inner img").attr("data-lazy-src")
        anime.title = document.select("header.entry-header h1.entry-title").text()
        anime.description = document.select("div.su-column-inner p").toString()
            .substringAfter("<br>").substringBefore("</p>")
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

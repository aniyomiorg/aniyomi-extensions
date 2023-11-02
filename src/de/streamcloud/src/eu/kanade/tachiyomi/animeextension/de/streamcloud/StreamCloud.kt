package eu.kanade.tachiyomi.animeextension.de.streamcloud

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class StreamCloud : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "StreamCloud"

    override val baseUrl = "https://streamcloud.movie"

    override val lang = "de"

    override val supportsLatest = false

    override val client = network.cloudflareClient

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#dle-content div.item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/beliebte-filme/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.thumb a").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("div.thumb a img").attr("src")
        anime.title = element.select("div.thumb a img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.name = document.select("#title span.title").text()
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(document.select("meta[property=\"og:url\"]").attr("content"))
        episodeList.add(episode)
        return episodeList.reversed()
    }

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers = Headers.headersOf("if-modified-since", ""))
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // Video Extractor

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframeurl = document.selectFirst("div.player-container-wrap > iframe")
            ?.attr("src")
            ?: error("No videos!")
        val iframeDoc = client.newCall(GET(iframeurl)).execute().use { it.asJsoup() }

        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("stape", "dood"))!!
        val items = iframeDoc.select("div._player ul._player-mirrors li")

        val videoList = items.flatMap { element ->
            val url = element.attr("data-link").run {
                takeIf { startsWith("https") }
                    ?: "https:$this"
            }

            runCatching {
                when {
                    url.contains("streamtape") && hosterSelection.contains("stape") -> {
                        streamtapeExtractor.videosFromUrl(url)
                    }
                    url.startsWith("https://dood") && hosterSelection.contains("dood") -> {
                        doodExtractor.videosFromUrl(url)
                    }
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", "Streamtape")
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else {
            otherList += this
        }
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (hoster?.let { video.quality.contains(it) } == true) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        for (video in otherList) {
            if (hoster?.let { video.quality.contains(it) } == true) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.thumb a").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("div.thumb a img").attr("src")
        anime.title = element.select("div.thumb a img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "#nextlink"

    override fun searchAnimeSelector(): String = "#dle-content div.item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/index.php?do=search&subaction=search&search_start=$page&full_search=0&story=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.select("#longInfo img").attr("src")
        anime.title = document.select("#title span.masha_index4").text()
        anime.genre = document.select("#longInfo span.masha_index10").text().split("/").joinToString(", ") { it }
        anime.description = document.select("#longInfo #storyline span p").text()
        if (document.select("#longInfo div[style] a").attr("href").toString().contains("director")) {
            anime.author = document.select("#longInfo div[style] a").toString().substringAfter("</span>").substringBefore("</a>")
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
            entries = arrayOf("Streamtape", "DoodStream")
            entryValues = arrayOf("https://streamtape.com", "https://dood.")
            setDefaultValue("https://streamtape.com")
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
            entries = arrayOf("Streamtape", "Doodstream")
            entryValues = arrayOf("stape", "dood")
            setDefaultValue(setOf("stape", "dood"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}

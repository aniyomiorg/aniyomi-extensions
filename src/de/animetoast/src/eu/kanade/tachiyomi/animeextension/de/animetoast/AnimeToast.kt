package eu.kanade.tachiyomi.animeextension.de.animetoast

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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
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

class AnimeToast : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeToast"

    override val baseUrl = "https://www.animetoast.cc"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.row div.col-md-4 div.video-item"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.item-thumbnail a").attr("href"))
        val document = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
        anime.thumbnail_url = document.select(".item-content p img").attr("src")
        anime.title = element.select("div.item-thumbnail a").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val file = document.select("a[rel=\"category tag\"]").text()
        if (file.contains("Serie")) {
            val elements = document.select("#multi_link_tab0")
            elements.forEach {
                val episode = parseEpisodesFromSeries(it)
                episodeList.addAll(episode)
            }
        } else {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
            episode.name = document.select("h1.light-title").text()
            episode.episode_number = 1F
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val episodeElements = element.select("div.tab-pane a")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.text().replace("Ep. ", "").toFloat()
        episode.name = element.text()
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
        val epcu = document.select("div.tab-pane a.current-link").text().replace("Ep. ", "").toInt()
        val ep = document.select("div.tab-pane a")
        ep.forEach {
            if (it.text().replace("Ep. ", "").toInt() == epcu) {
                val url = it.attr("href")
                val newdoc = client.newCall(GET(url)).execute().asJsoup()
                val element = newdoc.select("#player-embed")
                val hosterSelection = preferences.getStringSet("hoster_selection", setOf("voe", "dood"))
                for (elements in element) {
                    val link = element.select("a").attr("abs:href")
                    when {
                        link.contains("https://voe.sx") && hosterSelection?.contains("voe") == true -> {
                            val quality = "Voe"
                            val video = VoeExtractor(client).videoFromUrl(link, quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        }
                    }
                }
                for (elements in element) {
                    val link = element.select("iframe").attr("abs:src")
                    when {
                        link.contains("https://dood") && hosterSelection?.contains("dood") == true -> {
                            val quality = "DoodStream"
                            val video = DoodExtractor(client).videoFromUrl(link, quality, false)
                            if (video != null) {
                                videoList.add(video)
                            }
                        }
                    }
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
        anime.setUrlWithoutDomain(element.attr("href"))
        val document = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
        anime.thumbnail_url = document.select(".item-content p img").attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = ".nextpostslink"

    override fun searchAnimeSelector(): String = "div.item-thumbnail a[href]"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page/$page/?s=$query"
        return GET(url)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select(".item-content p img").attr("src")
        anime.title = document.select("h1.light-title.entry-title").text()
        anime.genre = document.select("a[rel=tag]").joinToString(", ") { it.text() }
        val heigt = document.select("div.item-content p img").attr("height")
        anime.description = document.select("div.item-content").toString()
            .substringAfter("$heigt\">").replace("</p>", "").substringBefore("<strong>Genre:").replace("<p>", "")
        anime.status = parseStatus(document.select("a[rel=\"category tag\"]").text())
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
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
            entries = arrayOf("Voe", "DoodStream")
            entryValues = arrayOf("https://voe.sx", "https://dood")
            setDefaultValue("https://voe.sx")
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
            entries = arrayOf("Voe", "DoodStream")
            entryValues = arrayOf("voe", "dood")
            setDefaultValue(setOf("voe", "dood"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}

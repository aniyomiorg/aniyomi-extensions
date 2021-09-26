package eu.kanade.tachiyomi.animeextension.it.dreamsub

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class DreamSub : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DreamSub"

    override val baseUrl = "https://dreamsub.cc"

    override val lang = "it"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.goblock-content.go-full div.tvBlock"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/search?page=${page - 1}")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.url = element.selectFirst("div.showStreaming a").attr("href")
        anime.title = element.select("div.tvTitle").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item.active:not(li:last-child)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        if (document.selectFirst(episodeListSelector()) == null) {
            return oneEpisodeParse(document)
        }
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    private fun oneEpisodeParse(document: Document): List<SEpisode> {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(document.location())
        episode.episode_number = 1F
        episode.name = document.selectFirst("ol.breadcrumb li a").text()
        episode.date_upload = System.currentTimeMillis()
        return listOf(episode)
    }

    override fun episodeListSelector() = "div.goblock.server-list li.ep-item:has(div.sli-btn)"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.selectFirst("div.sli-btn a").attr("href"))
        val epText = element.selectFirst("div.sli-name a").text()
        episode.episode_number = epText.substringAfter("Episodio ").substringBefore(":").toFloat()
        episode.name = epText.replace(": TBA", "")
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun videoListSelector() = "div#main-content.onlyDesktop a.dwButton"

    override fun videoFromElement(element: Element): Video {
        val referer = element.ownerDocument().location()
        val url = element.attr("href")
        val quality = element.firstElementSibling().text() + element.text()
        return Video(url, quality, url, null, Headers.headersOf("Referer", referer))
    }

    override fun List<Video>.sort(): List<Video> {
        val subPreference = preferences.getString("preferred_sub", "SUB ITA")!!
        val quality = preferences.getString("preferred_quality", "1080")!!
        val qualityList = mutableListOf<Video>()
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (video.quality.contains(quality)) {
                qualityList.add(preferred, video)
                preferred++
            } else {
                qualityList.add(video)
            }
        }
        preferred = 0
        for (video in qualityList) {
            if (video.quality.startsWith(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.url = element.selectFirst("div.showStreaming a").attr("href")
        anime.title = element.select("div.tvTitle").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "li.page-item.active:not(li:last-child)"

    override fun searchAnimeSelector(): String = "div.goblock-content.go-full div.tvBlock"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/search/?q=$query&page=${page - 1}")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.dc-info h1.dc-title a").text()
        anime.genre = document.select("div.dc-info div.dci-spe div.dcis a").joinToString { it.text() }
        anime.description = document.select("div.dc-info div.dci-desc span#tramaLong").firstOrNull()?.ownText()
        anime.status = parseStatus(document.select("div.dcis:contains(Data:)").text())
        anime.thumbnail_url = "https:" + document.selectFirst("div.dc-thumb img").attr("src")
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return if (statusString.contains("In Corso")) {
            SAnime.ONGOING
        } else if (statusString.contains("Conclusa")) {
            SAnime.COMPLETED
        } else {
            SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val href = element.select("a.thumb").attr("href")
        if (href.count { it == '/' } == 2) {
            anime.setUrlWithoutDomain(baseUrl + href)
        } else {
            anime.setUrlWithoutDomain(baseUrl + href.substringBeforeLast("/"))
        }
        anime.title = element.select("div.item-detail").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "div.vistaGriglia ul.grid-item li"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualità preferita"
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
        val subPref = ListPreference(screen.context).apply {
            key = "preferred_sub"
            title = "Preferisci sub o dub?"
            entries = arrayOf("sub", "dub")
            entryValues = arrayOf("SUB ITA", "ITA")
            setDefaultValue("SUB ITA")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(subPref)
    }
}

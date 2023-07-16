package eu.kanade.tachiyomi.animeextension.de.animestream

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
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
import java.util.Base64
import kotlin.Exception

class AnimeStream : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeStream"

    override val baseUrl = "https://animestream.world"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.listupd article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=&type=&order=popular")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.tip").attr("href"))
        anime.thumbnail_url = element.select("a.tip img").attr("src")
        anime.title = element.select("a.tip img").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div a.r"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episodeElement = document.select("div.eplister ul li")
        episodeElement.forEach {
            val episode = episodeFromElement(it)
            episodeList.add(episode)
        }

        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("a div.epl-num").text().toFloat()
        episode.name = element.select("a div.epl-title").text()
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        return episode
    }

    // Video Extractor

    @RequiresApi(Build.VERSION_CODES.O)
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("stape", "voe"))
        val options = document.select("select.mirror option")
        options.forEach {
            val url = String(Base64.getDecoder().decode(it.attr("value").toString()))
                .substringAfter("src=\"").substringBefore("\"")
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
        anime.thumbnail_url = element.select("a.tip img").attr("src")
        anime.title = element.select("a.tip img").attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.next"

    override fun searchAnimeSelector(): String = "div.listupd article"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.thumb img").attr("src")
        anime.title = document.select("h1.entry-title").text()
        anime.description = document.select("div.entry-content").text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "div a.r"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.tip").attr("href"))
        anime.thumbnail_url = element.select("a.tip img").attr("src")
        anime.title = element.select("a.tip img").attr("title")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=&type=&order=update")

    override fun latestUpdatesSelector(): String = "div.listupd article"

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

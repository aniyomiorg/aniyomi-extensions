package eu.kanade.tachiyomi.animeextension.de.moviedream

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

class Moviedream : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Moviedream"

    override val baseUrl = "https://moviedream.co"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.boxshow a.linkto"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/beliebtefilme?p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain("/" + element.attr("href"))
        anime.title = element.select("div.imgboxwiths").text()
        anime.thumbnail_url = "$baseUrl/" + element.select("div.imgboxwiths img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.righter"

    // Episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.name = document.select("div.filmboxsmall p").toString()
            .substringAfter("Originaltitel: </b>").substringBefore("<br>")
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(response.request.url.toString())
        episodeList.add(episode)
        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not Used")

    // Video urls

    override fun videoListSelector(): String = throw Exception("not Used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val links = document.select("div#streamlinks script")
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("stape", "dood", "voe"))
        links.forEach {
            val paremeter = it.toString()
                .substringAfter("decrypt(").substringBefore(")+")
            val link = getLink(paremeter)
            when {
                link.contains("https://streamtape.com") && hosterSelection?.contains("stape") == true -> {
                    val quality = "Streamtape"
                    val video = StreamTapeExtractor(client).videoFromUrl(link, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                link.contains("https://dood") && hosterSelection?.contains("dood") == true -> {
                    val quality = "Doodstream"
                    val video = try { DoodExtractor(client).videoFromUrl(link, quality, redirect = false) } catch (e: Exception) { null }
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                link.contains("https://voe.sx") && hosterSelection?.contains("voe") == true -> {
                    val quality = "Voe"
                    val video = VoeExtractor(client).videoFromUrl(link, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
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

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "a.righter"

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/suchergebnisse.php?p=$page&text=$query&sprache=Deutsch")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.select("div.filmboxsmall img").attr("src").replace("../..", "")
        anime.title = document.select("div.filmboxsmall p").toString()
            .substringAfter("Originaltitel: </b>").substringBefore("<br>")
        anime.genre = document.select("div.filmboxsmall p").toString()
            .substringAfter("Genre: </b>").substringBefore("<br>")
        anime.status = SAnime.COMPLETED
        anime.author = document.select("div.filmboxsmall p").toString()
            .substringAfter("Regisseur: </b>").substringBefore("<br>")
        anime.description = document.select("div.filmboxsmall p[style]").text()
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Streamtape", "Doodstream", "Voe")
            entryValues = arrayOf("https://streamtape.com", "https://dood", "https://voe.sx")
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
            entries = arrayOf("Streamtape", "Doodstream", "Voe")
            entryValues = arrayOf("stape", "dood", "voe")
            setDefaultValue(setOf("stape", "dood", "voe"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}

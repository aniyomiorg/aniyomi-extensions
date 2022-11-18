package eu.kanade.tachiyomi.animeextension.de.cinemathek

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.cinemathek.extractors.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.de.cinemathek.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class Cinemathek : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Cinemathek"

    override val baseUrl = "https://cinemathek.net"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.movies"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.poster a").attr("href"))
        anime.title = element.select("div.data h3").text()
        anime.thumbnail_url = element.select("div.poster img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = ".arrow_pag"

    // Episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
        episode.name = document.select("div.poster img").attr("alt")
        episodeList.add(episode)
        return episodeList.reversed()
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
        val player = document.select("ul#playeroptionsul li")
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("slare", "streamsb", "fmoon"))
        player.forEach {
            val id = it.attr("data-post")
            val nume = it.attr("data-nume")
            val ajax = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", body = "action=doo_player_ajax&post=$id&nume=$nume&type=movie".toRequestBody("application/x-www-form-urlencoded".toMediaType()))).execute().body!!.string()
            val url = ajax.substringAfter("embed_url\":\"").substringBefore("\",").replace("\\", "")
            when {
                url.contains("https://streamlare.com") && hosterSelection?.contains("slare") == true -> {
                    videoList.addAll(StreamlareExtractor(client).videosFromUrl(url))
                }
                url.contains("https://streamsb") && hosterSelection?.contains("streamsb") == true -> {
                    videoList.addAll(StreamSBExtractor(client).videosFromUrl(url, headers = headers, common = false))
                }
                url.contains("https://filemoon") && hosterSelection?.contains("fmoon") == true -> {
                    val videos = FilemoonExtractor(client).videoFromUrl(url)
                    if (videos != null) {
                        videoList.addAll(videos)
                    }
                }
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", null)
        val quality = preferences.getString("preferred_quality", "1080")!!
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
        } else otherList += this
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(quality)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        for (video in otherList) {
            if (video.quality.contains(quality)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        return newList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.thumbnail a").attr("href"))
        anime.title = element.select("div.details div.title a").text()
        anime.thumbnail_url = element.select("div.image img").attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = ".arrow_pag"

    override fun searchAnimeSelector(): String = "div.result-item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query")
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select(".poster > img").attr("src")
        anime.title = document.select("div.data > h1").text()
        anime.genre = document.select(".sgeneros > a").joinToString(", ") { it.text() }
        anime.status = SAnime.COMPLETED
        anime.author = document.select("div.persons div.person[itemprop=director] div.name a").joinToString(", ") { it.text() }
        anime.description = document.select(".wp-content > p:nth-child(1)").text()
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
            entries = arrayOf("Streamlare", "StreamSB", "Filemoon")
            entryValues = arrayOf("https://streamlare", "https://viewsb.com", "https://filemoon")
            setDefaultValue("https://viewsb.com")
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
            entries = arrayOf("Streamlare", "StreamSB", "Filemoon")
            entryValues = arrayOf("slare", "streamsb", "fmoon")
            setDefaultValue(setOf("slare", "streamsb", "fmoon"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
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
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
        screen.addPreference(videoQualityPref)
    }
}

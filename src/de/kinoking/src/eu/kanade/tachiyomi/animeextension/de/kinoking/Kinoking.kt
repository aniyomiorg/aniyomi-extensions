package eu.kanade.tachiyomi.animeextension.de.kinoking

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
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
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

class Kinoking : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kinoking"

    override val baseUrl = "https://kinoking.cc"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#featured-titles article.item"

    override fun popularAnimeRequest(page: Int): Request {
        val interceptor = client.newBuilder().addInterceptor(CloudflareInterceptor()).build()
        val headers = interceptor.newCall(GET(baseUrl)).execute().request.headers
        return GET(baseUrl, headers = headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.data a").attr("href"))
        anime.thumbnail_url = element.select("div.poster img[data-src]").attr("data-src")
        anime.title = element.select("div.poster img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val url = document.select("link[rel=canonical]").attr("href")
        val newdoc = client.newCall(GET(url, headers = Headers.headersOf("if-modified-since", ""))).execute().asJsoup()
        if (newdoc.select("link[rel=canonical]").attr("href").contains("/tvshows/")) {
            val episodeElement = newdoc.select("#seasons div.se-c")
            episodeElement.forEach {
                val episode = parseEpisodesFromSeries(it)
                episodeList.addAll(episode)
            }
        } else {
            val episode = SEpisode.create()
            episode.name = newdoc.select("div.data h1").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(newdoc.select("link[rel=canonical]").attr("href"))
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val episodeElements = element.select("div.se-a li")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.attr("class").substringAfter("mark-").toFloat()
        val staffel = element.select("div.numerando").text().substringBefore(" -")
        val folge = element.select("div.numerando").text().substringAfter("- ")
        episode.name = "Staffel $staffel Folge $folge : " + element.select("div.episodiotitle a").text()
        episode.setUrlWithoutDomain(element.select("div.episodiotitle a").attr("href"))
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val videoelement = document.select("li.dooplay_player_option ")
        videoelement.forEach {
            val post = it.attr("data-post")
            val nume = it.attr("data-nume")
            val type = it.attr("data-type")
            val videodoc = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", body = "action=doo_player_ajax&post=$post&nume=$nume&type=$type".toRequestBody("application/x-www-form-urlencoded".toMediaType()))).execute().body!!.string()
            val link = videodoc.substringAfter("\"embed_url\":\"").substringBefore("\",").replace("\\", "")
            val hosterSelection = preferences.getStringSet("hoster_selection", setOf("dood", "watchsb", "voe"))
            when {
                link.contains("https://watchsb") || link.contains("https://viewsb") && hosterSelection?.contains("watchsb") == true -> {
                    if (it.select("span.flag img").attr("data-src").contains("/en.")) {
                        val lang = "Englisch"
                        val video = StreamSBExtractor(client).videosFromUrl(link, headers, suffix = lang)
                        videoList.addAll(video)
                    } else {
                        val lang = "Deutsch"
                        val video = StreamSBExtractor(client).videosFromUrl(link, headers, lang)
                        videoList.addAll(video)
                    }
                }
                link.contains("https://dood.") || link.contains("https://doodstream.") && hosterSelection?.contains("dood") == true -> {
                    val quality = "Doodstream"
                    val redirect = !link.contains("https://doodstream")
                    val video = DoodExtractor(client).videoFromUrl(link, quality, redirect)
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
        anime.setUrlWithoutDomain(element.select("div.thumbnail a").attr("href"))
        anime.thumbnail_url = element.select("div.thumbnail img").attr("data-src")
        anime.title = element.select("div.thumbnail img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "#nextpagination"

    override fun searchAnimeSelector(): String = "div.search-page div.result-item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query", headers = Headers.headersOf("if-modified-since", ""))

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.poster img").attr("data-src")
        anime.title = document.select("div.data h1").text()
        anime.genre = document.select("div.sgeneros a").joinToString(", ") { it.text() }
        anime.description = document.select("div.wp-content p").text()
        anime.author = document.select("div.person[itemprop=director] div.name a").joinToString(", ") { it.text() }
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
            entries = arrayOf("Doodstream", "StreamSB", "Voe")
            entryValues = arrayOf("https://dood", "https://watchsb.com", "https://voe.sx")
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
            entries = arrayOf("Doodstream", "StreamSB", "Voe")
            entryValues = arrayOf("dood", "watchsb", "voe")
            setDefaultValue(setOf("dood", "watchsb", "voe"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}

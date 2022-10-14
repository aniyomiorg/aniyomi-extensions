package eu.kanade.tachiyomi.animeextension.de.anifreakz

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.anifreakz.extractors.AnimefreakzExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
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

class Anifreakz : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anifreakz"

    override val baseUrl = "https://anifreakz.com"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.row.row-cols-md-5 div.col div.list-movie"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?filter={\"sorting\":\"popular\"}&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.list-media").attr("href"))
        anime.thumbnail_url = element.select("a.list-media div.media.media-cover").attr("data-src")
        anime.title = element.select("div.list-caption a.list-title").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item:last-child a"

    // episodes

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}", headers = Headers.headersOf("if-modified-since", ""))
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = document.select("meta[property=\"og:url\"]").attr("content")
        val episodeList = mutableListOf<SEpisode>()
        if (url.contains("/serie/")) {
            val seasonElements = document.select("div.episodes.tab-content div.tab-pane")
            seasonElements.forEach {
                val episode = parseEpisodesFromSeries(it)
                episodeList.addAll(episode)
            }
        } else {
            val episode = SEpisode.create()
            episode.name = document.select("div.caption-content h1").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val season = element.attr("id")
            .substringAfter("season-")
        val episodeElements = element.select("a")
        return episodeElements.map { episodeFromElement(it, season) }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not Used")

    private fun episodeFromElement(element: Element, season: String): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = element.select("div.episode").text()
            .substringBefore(".Episode").toFloat()
        val folge = element.select("div.episode").text()
            .substringBefore(".Episode")
        episode.name = "Staffel $season Folge $folge : " + element.select("div.name").text()
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        if (!document.select("div.dropdown-menu[aria-labelledby=\"videoSource\"] button").isNullOrEmpty()) {
            val langs = document.select("div.dropdown-menu[aria-labelledby=\"videoSource\"] button")
            langs.forEach {
                val id = it.attr("data-embed")
                val hoster = it.select("span.language").text()
                val hostdoc = client.newCall(POST("$baseUrl/ajax/embed", body = "id=$id&captcha=".toRequestBody("application/x-www-form-urlencoded".toMediaType()))).execute().asJsoup()
                if (it.select("span.name").text().contains("GerSub")) {
                    val quality = "$hoster SUB"
                    val url = hostdoc.select("iframe").attr("src")
                    val video = AnimefreakzExtractor(client).videoFromUrl(url, quality)
                    if (video != null) {
                        videoList.addAll(video)
                    }
                } else {
                    val quality = "$hoster DUB"
                    val url = hostdoc.select("iframe").attr("src")
                    val video = AnimefreakzExtractor(client).videoFromUrl(url, quality)
                    if (video != null) {
                        videoList.addAll(video)
                    }
                }
            }
        } else {
            val id = document.select("div.nav-player-select a.dropdown-toggle").attr("data-embed")
            val hostdoc = client.newCall(POST("$baseUrl/ajax/embed", body = "id=$id&captcha=".toRequestBody("application/x-www-form-urlencoded".toMediaType()))).execute().asJsoup()
            val hoster = hostdoc.select("iframe").attr("src")
                .substringAfter("https://").substringBefore(".")
            if (document.select("div.nav-player-select a.dropdown-toggle span").text().contains("GerSub")) {
                val quality = "$hoster SUB"
                val url = hostdoc.select("iframe").attr("src")
                val video = AnimefreakzExtractor(client).videoFromUrl(url, quality)
                if (video != null) {
                    videoList.addAll(video)
                }
            } else {
                val quality = "$hoster DUB"
                val url = hostdoc.select("iframe").attr("src")
                val video = AnimefreakzExtractor(client).videoFromUrl(url, quality)
                if (video != null) {
                    videoList.addAll(video)
                }
            }
        }
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val subPreference = preferences.getString("preferred_sub", null)
        if (subPreference != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(subPreference)) {
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
        anime.setUrlWithoutDomain(element.select("a.list-media").attr("href"))
        anime.thumbnail_url = element.select("a.list-media div.media.media-cover").attr("data-src")
        // .substringAfter("url(\"").substringBefore("\");")
        anime.title = element.select("div.list-caption a.list-title").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = "div.row.row-cols-5 div.col div.list-movie"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val url = document.select("meta[property=\"og:url\"]").attr("content")
        if (url.contains("/serie/")) {
            anime.thumbnail_url = document.select("div.media.media-cover").attr("data-src")
            anime.title = document.select("div.col-md-9 div.pl-md-4 h1").text()
            anime.genre = document.select("div.col-md-9 div.pl-md-4 div.categories a").joinToString(", ") { it.text() }
            anime.description = document.select("div.text div.text-content").text()
            anime.author = document.select("div.featured-attr div.text a").joinToString(", ") { it.text() }
            anime.status = parseStatus(document.select("span.stato").text())
            return anime
        } else {
            anime.thumbnail_url = document.select("div.media.media-cover").attr("data-src")
            anime.title = document.select("div.caption-content h1").text()
            anime.genre = document.select("div.col-md-9 div.pl-md-4 div.categories a").joinToString(", ") { it.text() }
            anime.description = document.select("script[type=\"application/ld+json\"]").toString()
                .substringAfter("\"reviewBody\": \"").substringBefore("\"")
            anime.author = document.select("div.text[data-more] a").joinToString(", ") { it.text() }
            anime.status = parseStatus(document.select("span.stato").text())
            return anime
        }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Returning Series", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val subPref = ListPreference(screen.context).apply {
            key = "preferred_sub"
            title = "Standardmäßig Sub oder Dub?"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("SUB", "DUB")
            setDefaultValue("SUB")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(subPref)
    }
}

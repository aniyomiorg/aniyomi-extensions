package eu.kanade.tachiyomi.animeextension.it.animeworld

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.it.animeworld.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.it.animeworld.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animeextension.it.animeworld.extractors.StreamTapeExtractor
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

class ANIMEWORLD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "ANIMEWORLD.tv"

    override val baseUrl = "https://www.animeworld.tv"

    override val lang = "it"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.items div.item a.thumb"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/az-list?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("abs:href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.paging-wrapper a#go-next-page"

    // Episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.server.active ul.episodes li.episode a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = "Episode: " + element.text()
        val epNum = getNumberFromEpsString(element.text())
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video urls

    override fun videoListRequest(episode: SEpisode): Request {
        val iframe = baseUrl + episode.url
        return GET(iframe)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "center a[href*=dood], center a[href*=streamtape], center a[href*=animeworld.biz]"

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val url = element.attr("href")
            Log.i("lol", url)
            val location = element.ownerDocument().location()
            val videoHeaders = Headers.headersOf("Referer", location)
            when {
                url.contains("animeworld.biz") -> {
                    val headers = headers.newBuilder()
                        // .set("Referer", "https://sbplay2.com/")
                        .set("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("watchsb", "streamsb")
                        .build()
                    val videos = StreamSBExtractor(client).videosFromUrl(url.replace("/d/", "/e/"), headers)
                    videoList.addAll(videos)
                }
                url.contains("dood") -> {
                    val video = DoodExtractor(client).videoFromUrl(url.replace("/d/", "/e/"))
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                url.contains("streamtape") -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(url.replace("/v/", "/e/"))
                    if (video != null) {
                        videoList.add(video)
                    }
                }
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
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

    // search

    override fun searchAnimeSelector(): String = "div.film-list div.item div.inner a.poster"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("abs:href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.paging-wrapper a#go-next-page"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?keyword=$query&page=$page")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.thumb img").first().attr("src")
        anime.title = document.select("div.c1 h2.title").text()
        anime.genre = document.select("dd:has(a[href*=language]) a, dd:has(a[href*=genre]) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.desc").text()
        anime.author = document.select("dd:has(a[href*=studio]) a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("dd:has(a[href*=status]) a").text().replace("Status: ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "In corso" -> SAnime.ONGOING
            "Finito" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updated?page=$page")

    override fun latestUpdatesSelector(): String = "div.film-list div.item div.inner a.poster"

    override fun latestUpdatesNextPageSelector(): String = "div.paging-wrapper a#go-next-page"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("abs:href").substringBeforeLast("/"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("alt")
        return anime
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
            entryValues = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}

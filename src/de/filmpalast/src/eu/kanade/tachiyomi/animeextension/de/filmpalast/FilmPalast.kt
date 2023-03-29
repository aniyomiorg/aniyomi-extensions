package eu.kanade.tachiyomi.animeextension.de.filmpalast

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors.EvoloadExtractor
import eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors.UpstreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
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

class FilmPalast : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "FilmPalast"

    override val baseUrl = "https://filmpalast.to"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "article.liste > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies/top/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val file = element.select("img").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = element.attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.pageing:contains(vorwärts)"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.name = "Film"
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
        episodeList.add(episode)
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select("ul.currentStreamLinks > li > a")
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("voe", "stape", "evo", "up", "moon"))
        for (element in elements) {
            val url = element.attr("abs:href")
            when {
                url.contains("https://voe.sx") && hosterSelection?.contains("voe") == true -> {
                    val quality = "Voe"
                    val video = VoeExtractor(client).videoFromUrl(url, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                url.contains("https://upstream.to") && hosterSelection?.contains("up") == true -> {
                    val videos = UpstreamExtractor(client).videoFromUrl(url)
                    if (videos != null) {
                        videoList.addAll(videos)
                    }
                }
            }
        }
        for (element in elements) {
            val url = element.attr("abs:data-player-url")
            when {
                url.contains("https://streamtape.com") && hosterSelection?.contains("stape") == true -> {
                    try {
                        with(
                            client.newCall(GET(url, headers = Headers.headersOf("Referer", baseUrl, "Cookie", "Fuck Streamtape because they add concatenation to fuck up scrapers")))
                                .execute().asJsoup(),
                        ) {
                            linkRegex.find(this.select("script:containsData(document.getElementById('robotlink'))").toString())?.let {
                                val quality = "Streamtape"
                                val id = it.groupValues[1].replace("%27+%20(%27xcdb", "")
                                val videoUrl = "https://streamtape.com/get_video?$id&stream=1".replace("""" + '""", "")
                                videoList.add(Video(videoUrl, quality, videoUrl))
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
                url.contains("https://evoload.io") && hosterSelection?.contains("evo") == true -> {
                    val quality = "Evoload"
                    if (document.select("#EvoVid_html5_api").attr("src").contains("EvoStreams")) {
                        val videoUrl = document.select("#EvoVid_html5_api").attr("src")
                        if (videoUrl.isNotEmpty()) {
                            videoList.add(Video(videoUrl, quality, videoUrl))
                        }
                    } else {
                        EvoloadExtractor(client).videoFromUrl(url, quality)
                        videoList.addAll(EvoloadExtractor(client).videoFromUrl(url, quality))
                    }
                }
                url.contains("filemoon.sx") && hosterSelection?.contains("moon") == true -> {
                    val videos = FilemoonExtractor(client).videoFromUrl(url)
                    if (videos != null) {
                        videoList.addAll(videos)
                    }
                }
            }
        }

        return videoList
    }

    private val linkRegex =
        Regex("""(i(|" \+ ')d(|" \+ ')=.*?&(|" \+ ')e(|" \+ ')x(|" \+ ')p(|" \+ ')i(|" \+ ')r(|" \+ ')e(|" \+ ')s(|" \+ ')=.*?&(|" \+ ')i(|" \+ ')p(|" \+ ')=.*?&(|" \+ ')t(|" \+ ')o(|" \+ ')k(|" \+ ')e(|" \+ ')n(|" \+ ')=.*)'""")

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", "Voe")
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
        anime.setUrlWithoutDomain(element.attr("href"))
        val file = element.select("img").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.pageing:contains(vorwärts)"

    override fun searchAnimeSelector(): String = "article.liste > a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search/title/$query/$page"
        return GET(url)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val file = document.select("img.cover2").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = document.select("h2.bgDark").text()
        anime.genre = document.select("#detail-content-list > li:nth-child(2) > span").joinToString(", ") { it.text() }
        anime.description = document.select("#detail-content-list > li:nth-child(3) > span").text()
        anime.author = document.select("#detail-content-list > li:nth-child(4) > span").joinToString(", ") { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "a.pageing:contains(vorwärts)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val file = element.select("img").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = element.attr("title")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page")

    override fun latestUpdatesSelector(): String = "article.liste > a"

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Voe", "Streamtape", "Evoload", "Upstream", "Filemoon")
            entryValues = arrayOf("https://voe.sx", "https://streamtape.com", "https://evoload.io", "https://upstream.to", "https://filemoon.sx")
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
            entries = arrayOf("Voe", "Streamtape", "Evoload", "Upstream", "Filemoon")
            entryValues = arrayOf("voe", "stape", "evo", "up", "moon")
            setDefaultValue(setOf("voe", "stape", "evo", "up", "moon"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}

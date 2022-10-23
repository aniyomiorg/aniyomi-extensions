package eu.kanade.tachiyomi.animeextension.hi.animeWorld

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
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimeWorld : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeWorld (experimental)"

    override val baseUrl = "https://anime-world.in/"

    override val lang = "hi"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "li.series, li.movies"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        var url = element.select("img").first().attr("src")
        if (!url.contains("https")) {
            url = "https:$url"
        }
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = url
        anime.title = element.select("h2.entry-title").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:last-child:not(.selected)"

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (response.request.url.toString().contains("movies/")) {
            val episodeList = mutableListOf<SEpisode>()
            val episode = SEpisode.create()
            episode.name = document.select("h1.entry-title").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodeList.add(episode)
            episodeList
        } else {
            val seasonDataElements = document.select("li.sel-temp")
            val episodesList = mutableListOf<SEpisode>()
            seasonDataElements.map {
                val epList = episodeFromSeason(it)
                episodesList.addAll(epList)
            }
            episodesList
        }
    }

    private fun episodeFromSeason(element: Element): List<SEpisode> {
        val seasonNo = element.select("a").attr("data-season")
        val postNo = element.select("a").attr("data-post")
        val episodeList = mutableListOf<SEpisode>()
        val postUrl = "https://anime-world.in/wp-admin/admin-ajax.php"
        val body = FormBody.Builder()
            .add("action", "action_select_season")
            .add("season", seasonNo)
            .add("post", postNo)
            .build()
        val epListResponse = client.newCall(
            POST(
                postUrl,
                headers,
                body
            )
        ).execute().asJsoup()
        val episodesElements = epListResponse.select("li")
        episodesElements.map {
            val episode = SEpisode.create()
            val url = it.select("a").attr("href")
            episode.setUrlWithoutDomain(url)
            val epNo = it.select("span.num-epi").text()
            episode.name = "$epNo : ${it.select("h2.entry-title").text()}"
            episode.episode_number = epNo.substringAfter("x").toFloat()
            episodeList.add(episode)
        }
        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.attr("href"))
        val epName = element.selectFirst("div.name").ownText()
        val ep = epName.substringAfter("Episode ")
        val epNo = try {
            ep.substringBefore(" ").toFloat()
        } catch (e: NumberFormatException) {
            0.toFloat()
        }
        episode.episode_number = epNo
        episode.name = if (ep == epName) epName else "Episode $ep"
        return episode
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val playerElement = document.select("section.player")
        val languagesElement = playerElement.select("span.rtg")
        for (element in languagesElement) {
            val tabId = element.attr("tab")
            val language = element.text()
            val options = playerElement.select("div#$tabId li a")
            options.map {
                val optionId = it.attr("href")
                val videos = videosFromElement(playerElement.select("div$optionId").first(), language)
                videoList.addAll(videos)
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    private fun videosFromElement(element: Element, language: String): List<Video> {
        val iframeElm = element.select("iframe")
        val videoList = mutableListOf<Video>()
        val url = iframeElm.attr("data-src")
        when {
            url.contains("embedsb") || url.contains("cloudemb") || url.contains("sbembed.com") ||
                url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com")
            -> {
                val videos = StreamSBExtractor(client).videosFromUrl(url, headers, "$language:")
                videoList.addAll(videos)
            }
        }
        return videoList
    }
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

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        var url = element.select("img").first().attr("src")
        if (!url.contains("https")) {
            url = "https:$url"
        }
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = url
        anime.title = element.select("h2.entry-title").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "nav.navigation.pagination div.nav-links a:last-child:not(.current)"

    override fun searchAnimeSelector(): String = "div#movies-a li"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            else -> GET("$baseUrl/")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.entry-title").text()
        anime.description = document.select("div.description").text()
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
        screen.addPreference(videoQualityPref)
    }
}

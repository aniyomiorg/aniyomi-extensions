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
import eu.kanade.tachiyomi.util.asJsoup
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

    override val baseUrl = "https://anime-world.in"

    override val lang = "hi"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/advanced-search/page/$page/?s_keyword=&s_type=all&s_status=all&s_lang=all&s_sub_type=all&s_year=all&s_orderby=viewed&s_genre=")

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li:has(span[aria-current=\"page\"]) + li"

    override fun popularAnimeSelector(): String = "div.col-span-1"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        var thumbnail = element.selectFirst("img")!!.attr("src")
        if (!thumbnail.contains("https")) {
            thumbnail = "$baseUrl/$thumbnail"
        }
        anime.thumbnail_url = thumbnail
        anime.title = element.select("div.font-medium.line-clamp-2.mb-3").text()
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create().apply {
            genre = document.select("span.leading-6 a[class~=border-opacity-30]").joinToString(", ") { it.text() }
            description = document.select("span.block.w-full.max-h-24.overflow-scroll.my-3.overflow-x-hidden.text-xs.text-gray-200").text()
            author = document.select("span.leading-6 a[href*=\"producer\"]:first-child").text()
            artist = document.select("span.leading-6 a[href*=\"studio\"]:first-child").text()
            status = parseStatus(document)
        }
        return anime
    }

    private val selector = "ul li:has(div.w-1.h-1.bg-gray-500.rounded-full) + li"

    private fun parseStatus(document: Document): Int {
        return when (document.select("$selector a:not(:contains(Ep))").text()) {
            "Movie" -> SAnime.COMPLETED
            else -> {
                val episodeString = document.select("$selector a:not(:contains(TV))").text().drop(3).split("/")
                if (episodeString[0].trim().compareTo(episodeString[1].trim()) == 0) {
                    SAnime.COMPLETED
                } else SAnime.ONGOING
            }
        }
    }

    override fun episodeListSelector() = throw Exception("not used")

    // {"title":"Naruto - Episode 162","metadata":{"number":"162","title":"The Cursed Warrior","duration":"23m","released":"1687815266","parent_id":"1221","parent_name":"Naruto","parent_slug":"naruto","download":[],"thumbnail":"","anime_type":"series","anime_season":"","anime_id":""},"id":10819,"url":"https:\/\/anime-world.in\/watch\/naruto-episode-162\/","published":"13 hours Ago","image":""}

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.attr("href"))
        val epName = element.selectFirst("div.name")!!.ownText()
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
                val videos = videosFromElement(playerElement.select("div$optionId").first()!!, language)
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

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            else -> GET("$baseUrl/")
        }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/advanced-search/page/$page/?s_keyword=&s_type=all&s_status=all&s_lang=all&s_sub_type=all&s_year=all&s_orderby=update&s_genre=")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

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

package eu.kanade.tachiyomi.animeextension.en.rule34video

import android.app.Application
import android.content.SharedPreferences
import android.media.tv.TvContract
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
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

class Rule34Video : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com/"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Videos

    override fun popularAnimeSelector(): String = "div.item.thumb"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/latest-updates/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.th").attr("href"))
        anime.title = element.select("a.th div.thumb_title").text()
        anime.thumbnail_url = element.select("a.th div.img img").attr("data-original")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.item.pager.next a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        val jsoup = response.asJsoup()

        val episode = SEpisode.create().apply {
            name = "Video"
            date_upload = System.currentTimeMillis()
        }
        episode.setUrlWithoutDomain(response.request.url.toString())
        episodes.add(episode)

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val VideoLinks = document.select("div.video_tools div:nth-child(3) div a.tag_item")
            .map {
                it.attr("href") + it.text()
                    .replace("MP4", "")
            }

        val videoList = mutableListOf<Video>()

        for (i in VideoLinks) {
            if (i.split(" ")[1] == "2160p") {
                videoList.add(Video(i.split(" ")[0], "2160p", i.split(" ")[0]))
            }
            if (i.split(" ")[1] == "1080p") {
                videoList.add(Video(i.split(" ")[0], "1080p", i.split(" ")[0]))
            }
            if (i.split(" ")[1] == "720p") {
                videoList.add(Video(i.split(" ")[0], "720p", i.split(" ")[0]))
            }
            if (i.split(" ")[1] == "480p") {
                videoList.add(Video(i.split(" ")[0], "480p", i.split(" ")[0]))
            }
            if (i.split(" ")[1] == "360p") {
                videoList.add(Video(i.split(" ")[0], "360p", i.split(" ")[0]))
            }
        }

        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search/$query/?from_videos=$page", headers)
    }

    override fun searchAnimeSelector(): String = "div.item.thumb"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.th").attr("href"))
        anime.title = element.select("a.th div.thumb_title").text()
        anime.thumbnail_url = element.select("a.th div.img img").attr("data-original")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.item.pager.next a"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.title_video").text()
        anime.author = document.select("#tab_video_info div:nth-child(3) div div:nth-child(2) a").joinToString { it.text() }
        anime.description = document.select("#tab_video_info div:nth-child(2) div em").text() +
            "\n\nViews : ${document.select("#tab_video_info div.info.row div:nth-child(2) span").text().replace((" "), ",")}\n" +
            "Duration : ${document.select("#tab_video_info div.info.row div:nth-child(3) span").text()}\n"
        anime.genre = document.select("div.video_tools div:nth-child(4) div a").joinToString {
            if (it.text() != "+ | Suggest") it.text() else ""
        }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("1080p")
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

    // Filters

}

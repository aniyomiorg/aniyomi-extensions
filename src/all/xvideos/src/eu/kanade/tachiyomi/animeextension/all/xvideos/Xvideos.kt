package eu.kanade.tachiyomi.animeextension.all.xvideos

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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

class Xvideos : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Xvideos"

    override val baseUrl = "https://www.xvideos.com"

    override val lang = "all"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div#main div#content div.mozaique.cust-nb-cols > div"

    override fun popularAnimeRequest(page: Int): Request = GET("https://www.xvideos.com/new/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            "$baseUrl${element.select("div.thumb-inside div.thumb a").attr("href")}"
        )
        Log.i("bruh", "${element.select("div.thumb-inside div.thumb a > img").attr("data-src")}")
        anime.title = element.select("div.thumb-under p.title").text()
        anime.thumbnail_url = element.select("div.thumb-inside div.thumb a img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.no-page.next-page"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        val jsoup = response.asJsoup()

        val episode = SEpisode.create().apply {
            name = "Video"
            url = response.request.url.toString().replace("https://www.xvideos.com", "")
            date_upload = System.currentTimeMillis()
        }
        episodes.add(episode)

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("html5player.setVideoUrl")) {
                val lowQuality = script.data().substringAfter("setVideoUrlLow('").substringBefore("')")
                if (lowQuality != null) {
                    videoList.add(Video(lowQuality, "Low", lowQuality, null))
                }
                val highQuality = script.data().substringAfter("setVideoUrlHigh('").substringBefore("')")
                if (highQuality != null) {
                    videoList.add(Video(highQuality, "High", highQuality, null))
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "High")
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

        return when {
            query.isNotBlank() -> GET("$baseUrl/?k=$query&p=$page", headers)
            else -> GET("$baseUrl/tags/${getSearchParameters(filters)}/$page ")
        }
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h2.page-title").text()
        anime.description = ""
        anime.genre = document.select("div.video-metadata ul li a span").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Search by text does not affect the filter"),
        Tags("", "Tag")
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var finalstring = ""
        var tags = ""

        filters.forEach { filter ->
            when (filter) {
                is Tags -> {
                    tags = if (filter.state.isEmpty()) "" // default value
                    else filter.state
                }
            }
            finalstring += "$tags"
        }
        return finalstring
    }

    internal class Tags(val input: String, name: String) : AnimeFilter.Text(name)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("High", "Low")
            entryValues = arrayOf("High", "Low")
            setDefaultValue("High")
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

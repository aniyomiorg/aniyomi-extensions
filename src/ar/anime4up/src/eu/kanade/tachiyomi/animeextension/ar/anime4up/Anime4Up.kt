package eu.kanade.tachiyomi.animeextension.ar.anime4up

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
// import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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

class Anime4Up : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime4Up"

    override val baseUrl = "https://anime4up.com"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://w1.anime4up.com/") // https://s12.gemzawy.com https://moshahda.net
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.anime-list-content div.row div.col-lg-2"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-list-3/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").attr("src")
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster div.ehover6 a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.next"

    // episodes

    override fun episodeListSelector() = "div.episodes-list-content div#DivEpisodesList div.col-md-3" // "ul.episodes-links li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("div.episodes-card-container div.episodes-card div.ehover6 h3 a").attr("href"))
        // episode.episode_number = element.select("span:nth-child(3)").text().replace(" - ", "").toFloat()
        episode.name = element.select("div.episodes-card-container div.episodes-card div.ehover6 h3 a").text()
        episode.date_upload = System.currentTimeMillis()

        return episode
    }

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("src")
        Log.i("ifrme", document.selectFirst("iframe").attr("src"))
        val referer = response.request.url.encodedPath
        val newHeaders = Headers.headersOf("referer", baseUrl)
        // Log.i("newhead", newHeaders)
        val iframeResponse = client.newCall(GET(iframe, newHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "script:containsData(source)"

    private fun videosFromElement(element: Element): List<Video> {
        val data = element.data().substringAfter("sources: [").substringBefore("],")
        Log.i("videoss", element.data().substringAfter("sources: [").substringBefore("],"))
        val sources = data.split("file:\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringBefore("\"")
            val quality = source.substringAfter("label:\"").substringBefore("\"")
            val video = Video(src, quality, src, null)
            videoList.add(video)
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

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("div.anime-card-container div.anime-card-poster div.ehover6 img").attr("src")
        anime.setUrlWithoutDomain(element.select("div.anime-card-container div.anime-card-poster div.ehover6 a").attr("href"))
        anime.title = element.select("div.anime-card-container div.anime-card-poster div.ehover6 img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li a.next"

    override fun searchAnimeSelector(): String = "div.anime-list-content div.row.display-flex div.col-md-4"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/?search_param=animes&s=$query")

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.thumbnail").first().attr("src")
        anime.title = document.select("h1.anime-details-title").text()
        anime.genre = document.select("ul.anime-genres > li > a, div.anime-info > a").joinToString(", ") { it.text() }
        anime.description = document.select("p.anime-story").text()
        document.select("div.anime-info a").text()?.also { statusText ->
            when {
                statusText.contains("يعرض الان", true) -> anime.status = SAnime.ONGOING
                statusText.contains("مكتمل", true) -> anime.status = SAnime.COMPLETED
                else -> anime.status = SAnime.UNKNOWN
            }
        }

        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Settings

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

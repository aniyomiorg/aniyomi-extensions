package eu.kanade.tachiyomi.animeextension.en.wcostream

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

class WCOStream : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "WCOStream"

    override val baseUrl = "https://wcostream.cc"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://wcostream.cc/")
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.film-poster"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-list/ongoing?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href").replace("watch", "anime").substringBefore("-episode"))
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.title = element.select("a").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.tab-content div[role=tabpanel] li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("a > strong").text())
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.name = "Episode: " + element.select("a > strong").text()
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.encodedPath
        val newHeaders = Headers.headersOf("referer", baseUrl + referer)
        val iframe = document.selectFirst("div#servers-list ul.nav li a:contains(VidStream)").attr("data-embed")
        val getSKey = client.newCall(GET(iframe, newHeaders)).execute().body!!.string() // .asJsoup()
        val sKey = getSKey.substringAfter("window.skey = '").substringBefore("'")
        val apiHeaders = headers.newBuilder()
            .set("referer1", "$iframe")
            .build()
        val apiLink = iframe.replace("/e/", "/info/") + "&skey=" + sKey
        /*val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)*/
        val iframeResponse = client.newCall(GET(apiLink, apiHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse) // .selectFirst(videoListSelector())
    }

    override fun videoListSelector() = throw Exception("not used")

    private fun videosFromElement(element: Element): List<Video> {
        val masterUrl = element.text().substringAfterLast("file\":\"").substringBeforeLast("\"}").replace("\\/", "/")
        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
        val videoList = mutableListOf<Video>()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("hls").replace("\n", "") + "p"
            val videoUrl = masterUrl.substringBeforeLast("/") + "/" + it.substringAfter("\n").substringBefore("\n")
            videoList.add(Video(videoUrl, quality, videoUrl))
            // val video = Video(videoUrl, quality, videoUrl)
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
        anime.setUrlWithoutDomain(element.select("a").attr("href").replace("watch", "anime").substringBefore("-episode"))
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.title = element.select("a").attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "div.film-poster"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/?keyword=$query&page=$page")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.film-poster-img").first().attr("src")
        anime.title = document.select("h1.heading-name").text()
        anime.genre = document.select("span.item div.btn-quality strong, span.item a.btn-quality strong, div.row-line:contains(Genre) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.description p").text()
        anime.author = document.select("div.row-line:contains(Studio)").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("div.row-line:contains(Status)").text().replace("Status: ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Airing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // Latest

    override fun latestUpdatesSelector(): String = "div.film-poster"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime-list/recently-updated?page=$page")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href").replace("watch", "anime").substringBefore("-episode"))
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.title = element.select("a").attr("title")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

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

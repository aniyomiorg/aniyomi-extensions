package eu.kanade.tachiyomi.animeextension.ar.cartons4u

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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Cartoons4U : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "CARTOONS4U"

    override val baseUrl = "https://cartoons4u.net"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular

    override fun popularAnimeSelector(): String = "ul.MovieList.Rows.Alt li.TPostMv article.TPost a:has(div.Image)"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/movies/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        // anime.thumbnail_url = "https:" + element.select("div.Image figure img").attr("data-src") // .replace("//", "")
        anime.title = element.select("div.Title").text().replace("فيلم", "")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.wp-pagenavi a.next"

    // episodes

    override fun episodeListSelector() = "link[rel=canonical]"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.ownerDocument().select("header.Top h1").text()
        return episode
    }

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.select("span.Button.TrLnk").attr("data-id")
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "a.button.is-success"

    private fun videosFromElement(element: Element): List<Video> {
        val vidURL = element.attr("abs:href")
        val apiCall = client.newCall(POST(vidURL.replace("/v/", "/api/source/"))).execute().body!!.string()
        val data = apiCall.substringAfter("\"data\":[").substringBefore("],")
        val sources = data.split("\"file\":\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringAfter("\"file\":\"").substringBefore("\"").replace("\\/", "/")
            val quality = source.substringAfter("\"label\":\"").substringBefore("\"")
            val video = Video(vidURL, quality, src)
            videoList.add(video)
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

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

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        // anime.thumbnail_url = element.select("div.Image figure img").attr("data-src").replace("//", "")
        anime.title = element.select("div.Title").text().replace("فيلم", "")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.wp-pagenavi a.next"

    override fun searchAnimeSelector(): String = "ul.MovieList.Rows.Alt li.TPostMv article.TPost a:has(div.Image)"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page/$page/?s=$query".toHttpUrlOrNull()!!.newBuilder()
        return GET(url.build().toString(), headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val getThumbnail = document.select("figure.Objf img.lazy").attr("data-src")
        val getThumbnail1 = document.select("figure.Image img").attr("data-lazy-src")
        if (getThumbnail.startsWith("//")) {
            anime.thumbnail_url = "https:" + getThumbnail
        } else {
            anime.thumbnail_url = getThumbnail1
        }
        anime.title = document.select("header.Top h1.Title").text()
        anime.genre = document.select("p.Genre a").joinToString(", ") { it.text() }
        anime.description = document.select("div.Description p:first-child").text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
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

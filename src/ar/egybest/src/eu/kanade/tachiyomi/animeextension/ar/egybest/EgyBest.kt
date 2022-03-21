package eu.kanade.tachiyomi.animeextension.ar.egybest

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class EgyBest : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EgyBest"

    override val baseUrl = "https://www.egy.best"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.movies a.movie"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("img").attr("src")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("span.title").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.auto.load"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seriesLink = document.select("div.movie_img a").attr("href")
        Log.i("seriesLink", "$seriesLink")
        if (seriesLink.contains("series")) {
            val seasonUrl = seriesLink
            Log.i("seasonUrl", seasonUrl)
            val seasonsHtml = client.newCall(
                GET(
                    seasonUrl
                    // headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().asJsoup()
            Log.i("seasonsHtml", "$seasonsHtml")
            val seasonP = seasonsHtml.selectFirst("div.contents.movies_small")
            val seasonsElements = seasonP.select("a.movie")
            Log.i("seasonsElements", "$seasonsElements")
            seasonsElements.forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            val movieUrl = seriesLink
            val episode = SEpisode.create()
            episode.name = document.select("div.movie_title h1 span").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(movieUrl)
            episodeList.add(episode)
        }
        return episodeList
    }


    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val episodesUrl = element.attr("abs:href")
        val episodesHtml = client.newCall(
            GET(
                episodesUrl,
            )
        ).execute().asJsoup()
        val episodeElements = episodesHtml.select("div.movies_small a[href~=episode]")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("span.title").text())
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        val seasonName = element.ownerDocument().select("div.movie_title h1").text().replace(" مسلسل ", "")
        episode.name = "$seasonName : " + element.select("span.title").text()
        Log.i("episodelink", element.select("div.episodiotitle a").attr("abs:href"))
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        Log.i("loooo", "$document")
        val movUrl = document.select("div.movie_img a").attr("href")
        Log.i("looo", movUrl)
        val apiUrl = "https://zawmedia-api.herokuapp.com/egybest?url=$movUrl"
        return videosFromElement(apiUrl)
    }

    private fun videosFromElement(url: String): List<Video> {
        val newHeaders = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/98.0.4758.102 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
            .set("Sec-Fetch-Dest", "document")
            .build()
        val document = client.newCall(GET(url, newHeaders)).execute().asJsoup()
        Log.i("tessst", "$document")
        val jjson = document.text()
        Log.i("text", jjson)
        val data = document.text().substringAfter("[").substringBeforeLast("]")
        Log.i("loool", "$data")
        val sources = data.split("\"link\":\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringBefore("\"")
            Log.i("looo", src)
            val quality = source.substringAfter("quality\":").substringBefore("}")
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

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("img").attr("src")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("span.title").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.auto.load"

    override fun searchAnimeSelector(): String = "div.movies a.movie"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/explore/?q=$query")

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.movie_img a img").attr("src")
        anime.title = document.select("div.movie_title h1 span[itemprop=\"name\"]").text()
        anime.genre = document.select("tr:contains(النوع) td a, tr:contains(التصنيف) td a").joinToString(", ") { it.text() }
        anime.description = document.select("div.mbox").firstOrNull {
            it.text().contains("القصة")
        }?.text()?.replace("القصة ", "")
        // anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "a.auto.load"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("img").attr("src")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("span.title").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/movies/?page=$page")

    override fun latestUpdatesSelector(): String = "div.movies a.movie"

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

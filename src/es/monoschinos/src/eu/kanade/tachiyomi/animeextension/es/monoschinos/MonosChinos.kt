package eu.kanade.tachiyomi.animeextension.es.monoschinos

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.OkruExtractor
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.SolidFilesExtractor
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

class MonosChinos : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MonosChinos"

    override val baseUrl = "https://monoschinos2.com"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.heromain div.row div.col-md-4"

    override fun popularAnimeRequest(page: Int): Request = GET("https://monoschinos2.com/animes?p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("a").attr("href")
        )
        anime.title = element.select("a div.series div.seriesdetails h5").text()
        anime.thumbnail_url = element.select("a div.series div.seriesimg img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        val jsoup = response.asJsoup()
        val animeId = response.request.url.pathSegments.last().replace("-sub-espanol", "")
        Log.i("bruh", "$animeId")
        jsoup.select("div.heroarea2 div.heromain2 div.allanimes div.row.jpage.row-cols-md-6 div.col-item").forEach { it ->

            val epNum = it.attr("data-episode")
            Log.i("bruh", "Episode-$epNum")
            val episode = SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                url = "/ver/$animeId-episodio-$epNum"
                date_upload = System.currentTimeMillis()
            }
            episodes.add(episode)
        }

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("ul.dropcaps li").forEach { it ->
            val server = it.select("a").text()
            val urlBase64 = it.select("a").attr("data-player")
            val url1 = Base64.decode(urlBase64, Base64.DEFAULT)
            val url = String(url1).replace("https://monoschinos2.com/reproductor?url=", "")
            Log.i("bruh", "$url")

            if (server == "fembed") {
                val videos = FembedExtractor().videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "ok") {
                val videos = OkruExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "Zeus") {
                val videos = SolidFilesExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Fembed: 720p")
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/buscar?q=$query&p=$page")

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.chapterpic img").attr("src")
        anime.title = document.selectFirst("div.chapterdetails h1").text()
        anime.description = document.select("p.textShort").first().ownText()
        anime.genre = document.select("ol.breadcrumb li.breadcrumb-item a").text()
        anime.status = parseStatus(document.select("div.butns button.btn1").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "SolidFiles", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile")
            entryValues = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "SolidFiles", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile")
            setDefaultValue("Fembed:720p")
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

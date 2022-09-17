package eu.kanade.tachiyomi.animeextension.es.animeyt

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animeyt.extractors.FastreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Animeyt : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animeyt"

    override val baseUrl = "https://ytanime.tv"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.video-block div.row div.col-md-2 div.video-card"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ultimos-animes?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.video-card div.video-card-body div.video-title a").attr("href"))
        anime.title = element.select("div.video-card div.video-card-body div.video-title a").text()
        anime.thumbnail_url = element.select("div.video-card div.video-card-image a:nth-child(2) img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item:last-child a"

    override fun episodeListSelector() = "#caps ul.list-group li.list-group-item a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("span.sa-series-link__number").text())
        episode.setUrlWithoutDomain(element.attr("href"))
        val epParsed = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        episode.episode_number = epParsed
        episode.name = "Episodio $epParsed"
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("#plays iframe").forEach { container ->
            val server = container.attr("src")
                .split(".")[0]
                .replace("https://", "")
                .replace("http://", "")
            val url = container.attr("src")
            if (server == "fastream") {
                FastreamExtractor(client).videoFromUrl(url).forEach { videoList.add(it) }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) }
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "Fastream:720p")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                videoSorted.drop(preferredIdx + 1)
                videoSorted[0] = videoSorted[preferredIdx]
            }
            videoSorted.toList()
        } catch (e: Exception) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?q=$query&page=$page")

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.sa-series-dashboard__poster div.sa-layout__line.sa-layout__line--sm div figure.sa-poster__fig img").attr("src")
        anime.title = document.selectFirst("#info div.sa-layout__line div div.sa-title-series__title span").html()
        anime.description = document.selectFirst("#info div.sa-layout__line p.sa-text").text().removeSurrounding("\"")
        // anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("#info > div:nth-child(2) > button").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Fastream:720p", "Fastream:480p", "Fastream:360p")
            entryValues = arrayOf("Fastream:720p", "Fastream:480p", "Fastream:360p")
            setDefaultValue("Fastream:720p")
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
